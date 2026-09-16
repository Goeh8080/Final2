package com.wnmsolutions.granth;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    // Live site — change here if the domain ever changes
    private static final String SITE_URL = "https://granth.wnmsolutions.com/";

    private WebView webView;
    private OfflineCachingWebViewClient cachingClient;
    private View networkStatusDot;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        networkStatusDot = findViewById(R.id.networkStatusDot);

        // Photos on Android 6–9 need this to save into the public Downloads folder.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // Pinch-to-zoom support (photos, pages — whole viewport)
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // Lets injected page JS trigger a native photo download
        webView.addJavascriptInterface(new ImageSaver(this), "AndroidImageSaver");

        // This is where the offline-caching magic happens.
        cachingClient = new OfflineCachingWebViewClient(this);
        webView.setWebViewClient(cachingClient);
        webView.setWebChromeClient(new WebChromeClient());

        // Lets injected page JS ask "is this item saved for offline?" (green/yellow dot)
        webView.addJavascriptInterface(new CacheChecker(cachingClient), "AndroidCacheChecker");

        setupNetworkStatusDot();

        if (savedInstanceState == null) {
            webView.loadUrl(SITE_URL);
        }
    }

    private void setupNetworkStatusDot() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        updateNetworkStatusDot(isOnline());

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                runOnUiThread(() -> updateNetworkStatusDot(true));
            }

            @Override
            public void onLost(@NonNull Network network) {
                runOnUiThread(() -> updateNetworkStatusDot(isOnline()));
            }
        };
        NetworkRequest request = new NetworkRequest.Builder().build();
        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private boolean isOnline() {
        if (connectivityManager == null) return false;
        Network network = connectivityManager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void updateNetworkStatusDot(boolean online) {
        networkStatusDot.setBackgroundResource(online ? R.drawable.status_dot_green : R.drawable.status_dot_red);
    }

    @Override
    protected void onDestroy() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * Called from injected JS (see OfflineCachingWebViewClient#onPageFinished)
     * when the small download button on a photo is tapped.
     */
    public static class ImageSaver {
        private final Context context;

        ImageSaver(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public void saveImage(String url) {
            try {
                Uri uri = Uri.parse(url);
                String fileName = "GranthPrabandhan_" + System.currentTimeMillis() + guessExtension(url);

                DownloadManager.Request request = new DownloadManager.Request(uri);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                request.setTitle("Granth Prabandhan");
                request.setDescription("Photo download ho rahi hai...");

                DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    if (context instanceof Activity) {
                        ((Activity) context).runOnUiThread(() ->
                                Toast.makeText(context, "Download shuru ho gaya", Toast.LENGTH_SHORT).show());
                    }
                }
            } catch (Exception e) {
                if (context instanceof Activity) {
                    ((Activity) context).runOnUiThread(() ->
                            Toast.makeText(context, "Download fail ho gaya", Toast.LENGTH_SHORT).show());
                }
            }
        }

        private String guessExtension(String url) {
            String lower = url.toLowerCase();
            if (lower.contains(".png")) return ".png";
            if (lower.contains(".webp")) return ".webp";
            if (lower.contains(".gif")) return ".gif";
            return ".jpg";
        }
    }

    /**
     * Called from injected JS to check, synchronously, whether a topic/granth's
     * data is already saved for offline use (drives the green/yellow dot).
     */
    public static class CacheChecker {
        private final OfflineCachingWebViewClient client;

        CacheChecker(OfflineCachingWebViewClient client) {
            this.client = client;
        }

        @JavascriptInterface
        public boolean isCached(String url) {
            return client.isCached(url);
        }
    }
}
