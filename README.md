# Granth Prabandhan — Offline WebView App

Ye ek Android Studio project hai jo `https://granth.wnmsolutions.com` ko WebView mein
kholta hai aur har GET request (pages, CSS, JS, images, `?action=...` ya
`api/index.php?request=...` — sab) ko phone ke internal storage mein cache kar deta
hai. Ek baar online dekha hua content dobara bina internet ke bhi khulta rahega.

## Kaise build karein

1. Android Studio khol kar **Open** → is `GranthOfflineApp` folder ko select karo.
2. "Trust Project" aaye to accept karo, Gradle sync hone do (pehli baar thoda time lega).
3. Upar toolbar mein **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. APK yahin milega: `app/build/outputs/apk/debug/app-debug.apk`
5. Us APK ko phone mein install karo (USB se ya file transfer karke).

## Kaise kaam karta hai

- `MainActivity.java` — WebView setup, live site load karta hai.
- `OfflineCachingWebViewClient.java` — asli caching logic. Har GET request pehle
  network se try hota hai, response `filesDir/webcache/` mein save hota hai. Agar
  network na ho to wahi saved copy serve ho jaati hai.
- Login/Add/Edit/Delete jaise POST actions cache nahi hote (wo hamesha internet
  maangenge — normal browser jaisa hi behavior).
- `assets/offline.html` — agar koi page pehli baar hi offline khola jaaye (kabhi
  cache hi nahi hua), to ye friendly message dikhta hai.

## Update (fixes)

- Pinch-to-zoom ab on hai (photos aur pages dono).
- Har photo ke upar chota ⬇ button hai — tap karke wo photo phone ke Downloads folder mein save ho jaati hai.
- Offline mode mein ek page dekhne ke baad "back" dabane par jo error aata tha (kyunki page ka URL scroll-position ke saath thoda badal jaata tha) — ab fix hai; back se wahi cached page dikhega.
- Dashboard ka cursive/stylish font ("Yatra One") normal font ("DM Sans") mein badal diya gaya hai — ye badlaav **website ke style.css** mein hai (Android project mein nahi), isliye ise apne hosting/server par upload karna hoga taaki live site aur app dono par effect ho.

## Update 2 (naya round)

- **App icon + splash video** daal diye — apna diya hua icon (`100544.jpg`) sab densities mein resize ho gaya, aur splash video (`splash.mp4`, ~10s) app khulte hi ek baar chalega, khatam hote hi seedha app khul jaayega.
- **Network status dot** — top-right corner mein chota dot: 🟢 hara = internet connected, 🔴 laal = offline chal raha hai. Live update hota hai.
- **Login/Admin/Feedback fix** — asli wajah ye thi ki offline-caching wala system server ko cookie aur AJAX headers (jaise X-Requested-With) bhejta hi nahi tha, isliye server ko lagta tha user logged-in hi nahi hai. Ab har request ke saath session cookie aur original headers bhejte hain, aur server se aaya naya cookie bhi save karte hain — login/admin/feedback sab normal kaam karna chahiye.
- **Offline search fix** — 200 topics/granths mein search box ab offline bhi kaam karega. Pehli baar online list load hote hi poori list cache ho jaati hai; offline mein search karne par usi cached list ko app khud filter kar deta hai (server se dobara maangta hi nahi).
- **2-column "dense" view** — Topics/Granths/Pramans ke search box ke bagal mein ek chota ⊞ button hai. Dabao to list 2 column mein simat jaati hai (jaldi scan karne ke liye), dobara dabao to normal ek-column wapas. Ye sirf CSS layout change hai — asli "Desktop mode" (jo user-agent badalta hai aur baaki cheezein tod sakta hai) use nahi kiya.
- **Green/Yellow availability dot** — har topic/granth card ke title ke paas ek chota dot: 🟢 matlab ye already offline ke liye saved hai, 🟡 matlab abhi tak offline available nahi (ek baar online khol kar dekh lo, phir ho jaayega).

## Badlaav (agar chahiye)

- Domain change karna ho to `MainActivity.java` mein `SITE_URL` badlo.
- App ka naam/icon `AndroidManifest.xml` aur `res/values/strings.xml` mein hai —
  abhi default Android icon use ho raha hai, apna launcher icon daalne ke liye
  `res/mipmap-*` folders mein `ic_launcher.png` daal kar manifest mein
  `android:icon="@mipmap/ic_launcher"` kar dena.
- Cache clear karne ka option chahiye (settings mein) to bata dena, add kar dunga.
