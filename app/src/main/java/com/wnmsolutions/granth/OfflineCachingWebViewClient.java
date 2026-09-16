package com.wnmsolutions.granth;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every GET request the WebView makes (pages, CSS, JS, images, and any
 * ?action=... / api/index.php?request=... JSON calls) passes through here.
 *
 * - Online:  fetch fresh (with the same cookies/headers the page would have
 *            sent) -> save a copy to internal storage -> serve it.
 * - Offline: serve the previously saved copy, if one exists. For a live
 *            search box (?topic=/&granth=/&praman=) that was never fetched
 *            with that exact text before, fall back to filtering the full
 *            list we already cached.
 *
 * Write requests (POST — login, create/update/delete) are left alone and
 * simply fail offline, same as they would on a normal browser.
 */
public class OfflineCachingWebViewClient extends WebViewClient {

    private static final String TAG = "OfflineCache";

    private final Context context;
    private final File cacheDir;

    public OfflineCachingWebViewClient(Context context) {
        this.context = context.getApplicationContext();
        this.cacheDir = new File(this.context.getFilesDir(), "webcache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    /** Used by MainActivity's JS bridge to show a green/yellow dot per item. */
    public boolean isCached(String url) {
        try {
            File dataFile = cacheFile(normalizeForCache(Uri.parse(url)), "data");
            return dataFile.exists();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        String method = request.getMethod();
        Uri uri = request.getUrl();
        String scheme = uri.getScheme();

        boolean cacheable = "GET".equalsIgnoreCase(method)
                && scheme != null
                && (scheme.equals("http") || scheme.equals("https"));

        if (!cacheable) {
            return super.shouldInterceptRequest(view, request);
        }

        String url = uri.toString();
        String cacheKey = normalizeForCache(uri);
        File dataFile = cacheFile(cacheKey, "data");
        File metaFile = cacheFile(cacheKey, "meta");

        if (isNetworkAvailable()) {
            try {
                WebResourceResponse fresh = fetchAndCache(url, dataFile, metaFile, request.getRequestHeaders());
                if (fresh != null) {
                    return fresh;
                }
            } catch (Exception e) {
                Log.w(TAG, "Network fetch failed, falling back to cache: " + url, e);
            }
        }

        if (dataFile.exists() && metaFile.exists()) {
            WebResourceResponse cached = serveFromCache(dataFile, metaFile);
            if (cached != null) {
                return cached;
            }
        }

        WebResourceResponse searchFallback = trySearchFallback(uri);
        if (searchFallback != null) {
            return searchFallback;
        }

        return super.shouldInterceptRequest(view, request);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        view.evaluateJavascript(PAGE_ENHANCEMENTS_JS, null);
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        super.onReceivedError(view, request, error);
        if (request.isForMainFrame()) {
            // Nothing cached for this page and no network — show a friendly local page.
            view.loadUrl("file:///android_asset/offline.html");
        }
    }

    // ---------------------------------------------------------------------
    // Network fetch + cookie handling
    // ---------------------------------------------------------------------

    private WebResourceResponse fetchAndCache(String urlStr, File dataFile, File metaFile,
                                               Map<String, String> requestHeaders) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setInstanceFollowRedirects(true);

        // Forward the page's own request headers (X-Requested-With, Accept, etc.)
        // so the PHP backend treats this exactly like a normal AJAX call.
        if (requestHeaders != null) {
            for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
                String key = header.getKey();
                if (key == null) continue;
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.equals("host") || lower.equals("content-length") || lower.equals("cookie")) {
                    continue; // handled separately / set automatically
                }
                conn.setRequestProperty(key, header.getValue());
            }
        }

        // Forward the logged-in session cookie — without this, login/admin/feedback
        // pages look logged-out when fetched through this cache layer.
        String cookie = CookieManager.getInstance().getCookie(urlStr);
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }

        int status = conn.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            return null;
        }

        // Save any session cookie the server sent back (e.g. after login).
        List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
        if (setCookies != null) {
            CookieManager cm = CookieManager.getInstance();
            for (String sc : setCookies) {
                cm.setCookie(urlStr, sc);
            }
            cm.flush();
        }

        String mimeType = extractMime(conn.getContentType(), urlStr);
        byte[] bytes;

        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) != -1) {
                buffer.write(tmp, 0, n);
            }
            bytes = buffer.toByteArray();
        } finally {
            conn.disconnect();
        }

        try (FileOutputStream fos = new FileOutputStream(dataFile)) {
            fos.write(bytes);
        }
        try (FileOutputStream fos = new FileOutputStream(metaFile)) {
            fos.write(mimeType.getBytes(StandardCharsets.UTF_8));
        }

        return new WebResourceResponse(mimeType, "UTF-8", new ByteArrayInputStream(bytes));
    }

    private WebResourceResponse serveFromCache(File dataFile, File metaFile) {
        try {
            String mimeType = readFileAsString(metaFile).trim();
            return new WebResourceResponse(mimeType, "UTF-8", new FileInputStream(dataFile));
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Offline "live search" fallback — filters the already-cached full list
    // (getTopics / getGranths / getPramans with no search text) instead of
    // trying to hit the network for every keystroke.
    // ---------------------------------------------------------------------

    private WebResourceResponse trySearchFallback(Uri uri) {
        String action = uri.getQueryParameter("action");
        if (action == null) return null;

        String searchParam;
        if ("get_topics".equals(action)) searchParam = "topic";
        else if ("get_granths".equals(action)) searchParam = "granth";
        else if ("get_pramans".equals(action)) searchParam = "praman";
        else return null;

        String query = uri.getQueryParameter(searchParam);
        if (query == null || query.trim().isEmpty()) return null;

        Uri.Builder builder = uri.buildUpon().clearQuery();
        for (String key : uri.getQueryParameterNames()) {
            if (key.equals(searchParam)) continue;
            for (String value : uri.getQueryParameters(key)) {
                builder.appendQueryParameter(key, value);
            }
        }
        String baseUrl = builder.build().toString();
        File baseData = cacheFile(normalizeForCache(Uri.parse(baseUrl)), "data");
        if (!baseData.exists()) return null;

        try {
            String json = readFileAsString(baseData);
            JSONObject obj = new JSONObject(json);
            JSONArray list = obj.optJSONArray("data");
            if (list == null) return null;

            JSONArray filtered = new JSONArray();
            String needle = query.toLowerCase(Locale.ROOT);
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                String title = item.optString("title", "");
                if (title.toLowerCase(Locale.ROOT).contains(needle)) {
                    filtered.put(item);
                }
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("data", filtered);
            byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
            return new WebResourceResponse("application/json", "UTF-8", new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            Log.w(TAG, "offline search fallback failed", e);
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private String readFileAsString(File file) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] tmp = new byte[1024];
            int n;
            while ((n = fis.read(tmp)) != -1) {
                buffer.write(tmp, 0, n);
            }
        }
        return buffer.toString("UTF-8");
    }

    private File cacheFile(String url, String kind) {
        return new File(cacheDir, md5(url) + "." + kind);
    }

    /**
     * Some pages add a purely-cosmetic query param (e.g. ?scrollTo=4) when the
     * WebView navigates back, just to restore scroll position. If we cached the
     * page under the plain URL, that variant would look like a cache miss and
     * fail offline. Strip known cosmetic params so back/forward reuses the same
     * cached entry as the original page.
     */
    private String normalizeForCache(Uri uri) {
        Uri.Builder builder = uri.buildUpon().clearQuery();
        for (String key : uri.getQueryParameterNames()) {
            if ("scrolltop".equalsIgnoreCase(key) || "scrollto".equalsIgnoreCase(key)) {
                continue;
            }
            for (String value : uri.getQueryParameters(key)) {
                builder.appendQueryParameter(key, value);
            }
        }
        return builder.build().toString();
    }

    private String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private String extractMime(String contentType, String url) {
        if (contentType != null && !contentType.isEmpty()) {
            int idx = contentType.indexOf(';');
            return idx > -1 ? contentType.substring(0, idx).trim() : contentType.trim();
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".css")) return "text/css";
        if (lower.contains(".js")) return "application/javascript";
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".jpg") || lower.contains(".jpeg")) return "image/jpeg";
        if (lower.contains(".gif")) return "image/gif";
        if (lower.contains(".webp")) return "image/webp";
        if (lower.contains("action=") || lower.contains("request=")) return "application/json";
        return "text/html";
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
        return capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    // ---------------------------------------------------------------------
    // Injected JS: photo download buttons, a "2-column" density toggle next
    // to each search box, and a green/yellow availability dot per card.
    // ---------------------------------------------------------------------

    private static final String PAGE_ENHANCEMENTS_JS =
            "(function(){"
            + "try{"

            // --- 1) Download button on every photo ---
            + "function addImgButton(img){"
            + "  if(!img || img.dataset.dlAttached) return;"
            + "  img.dataset.dlAttached='1';"
            + "  var wrap=img.parentElement; if(!wrap) return;"
            + "  if(window.getComputedStyle(wrap).position==='static') wrap.style.position='relative';"
            + "  var btn=document.createElement('div');"
            + "  btn.textContent='\\u2B07';"
            + "  btn.style.cssText='position:absolute;top:6px;right:6px;width:30px;height:30px;"
            +     "border-radius:50%;background:rgba(0,0,0,0.55);color:#fff;display:flex;"
            +     "align-items:center;justify-content:center;font-size:16px;z-index:9999;cursor:pointer;';"
            + "  btn.addEventListener('click', function(e){"
            + "    e.stopPropagation(); e.preventDefault();"
            + "    if(window.AndroidImageSaver){ AndroidImageSaver.saveImage(img.src); }"
            + "  });"
            + "  wrap.appendChild(btn);"
            + "}"

            // --- 2) 2-column density toggle button next to search boxes ---
            + "if(!document.getElementById('gp-density-style')){"
            + "  var st=document.createElement('style'); st.id='gp-density-style';"
            + "  st.textContent='body.gp-2col .grid-3, body.gp-2col .grid-4 {"
            +      "grid-template-columns:repeat(2,1fr) !important; gap:10px !important;} "
            +      "body.gp-2col .card{padding:10px !important;} "
            +      "body.gp-2col .card-title{font-size:13px !important;}';"
            + "  document.head.appendChild(st);"
            + "}"
            + "if(localStorage.getItem('gp_2col')==='1'){ document.body.classList.add('gp-2col'); }"
            + "function addDensityToggle(input){"
            + "  if(!input || input.dataset.densityAttached) return;"
            + "  input.dataset.densityAttached='1';"
            + "  var btn=document.createElement('button'); btn.type='button';"
            + "  btn.textContent='\\u229E'; btn.title='2 column view';"
            + "  btn.style.cssText='margin-left:6px;width:34px;height:34px;border-radius:8px;border:1px solid #c9a84c;"
            +     "background:#fff;cursor:pointer;font-size:16px;';"
            + "  function sync(){ btn.style.background = document.body.classList.contains('gp-2col') ? '#c9a84c' : '#fff'; }"
            + "  btn.addEventListener('click', function(){"
            + "    document.body.classList.toggle('gp-2col');"
            + "    localStorage.setItem('gp_2col', document.body.classList.contains('gp-2col') ? '1':'0');"
            + "    sync();"
            + "  });"
            + "  sync();"
            + "  input.insertAdjacentElement('afterend', btn);"
            + "}"
            + "['topic_search','granth_search','praman_search'].forEach(function(id){"
            + "  addDensityToggle(document.getElementById(id));"
            + "});"

            // --- 3) green/yellow offline-availability dot on each card ---
            + "function urlFor(kind, id){"
            + "  var base = kind==='topic' ? ('?action=get_pramans&topicId='+id) : ('?action=get_pramans&granthId='+id);"
            + "  return new URL(base, location.href).href;"
            + "}"
            + "function addDot(card, kind, id){"
            + "  if(!card || card.dataset.dotAttached) return;"
            + "  card.dataset.dotAttached='1';"
            + "  var header=card.querySelector('.card-header')||card;"
            + "  if(window.getComputedStyle(header).position==='static') header.style.position='relative';"
            + "  var dot=document.createElement('span');"
            + "  dot.style.cssText='display:inline-block;width:10px;height:10px;border-radius:50%;margin-left:6px;vertical-align:middle;';"
            + "  var ok=false;"
            + "  try{ if(window.AndroidCacheChecker){ ok = AndroidCacheChecker.isCached(urlFor(kind,id)); } }catch(e){}"
            + "  dot.style.background = ok ? '#2fb344' : '#e0b400';"
            + "  dot.title = ok ? 'Offline available' : 'Open once online to save';"
            + "  var title=card.querySelector('.card-title');"
            + "  (title||header).appendChild(dot);"
            + "}"
            + "function scanCards(){"
            + "  document.querySelectorAll('#topicsList .card, #topicsList > div.card').forEach(function(c){"
            + "    var id=c.id; if(id) addDot(c,'topic',id);"
            + "  });"
            + "  document.querySelectorAll('#granthsList .card, #granthsList > div.card').forEach(function(c){"
            + "    var id=c.id; if(id) addDot(c,'granth',id);"
            + "  });"
            + "}"
            + "scanCards();"

            // --- observe DOM for dynamically re-rendered lists/images ---
            + "document.querySelectorAll('img').forEach(addImgButton);"
            + "var obs=new MutationObserver(function(muts){"
            + "  muts.forEach(function(m){"
            + "    m.addedNodes.forEach(function(node){"
            + "      if(node.nodeType!==1) return;"
            + "      if(node.tagName==='IMG') addImgButton(node);"
            + "      else if(node.querySelectorAll){ node.querySelectorAll('img').forEach(addImgButton); }"
            + "    });"
            + "  });"
            + "  scanCards();"
            + "});"
            + "obs.observe(document.body, {childList:true, subtree:true});"
            + "}catch(e){ console.log('gp-enhancements error', e); }"
            + "})();";
}
