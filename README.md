# Vibe Launcher — a tiny, fast Android TV launcher

A minimal home-screen launcher for Android TV:

- **Favourites row** pinned at the top — hold **OK** on any app to pin/unpin it.
- **Live search** box to filter your apps.
- **Grid** of all installed apps.
- **Auto-rotating nature wallpaper** pulled from Bing's daily image feed
  (curated photography, changes by itself each day, no API key needed).

No recommendation rows, no ads, no background services — that's why it feels snappy.

You do **not** need Android Studio. GitHub builds the APK for you in the cloud;
you just need a small `adb` download to install it on the TV.

---

## 1. Build the APK (in the cloud, no toolchain to install)

1. Create a free account at github.com if you don't have one.
2. Make a **new repository** (name it anything, e.g. `vibe-launcher`).
3. Upload **all these files** into it, keeping the folder structure:
   - On the new repo page, click **"uploading an existing file"** and
     drag the whole project in. (The `.github`, `app`, and `gradle`
     folders must keep their paths.)
4. Commit. The **Actions** tab will start a build automatically.
5. When it finishes (a couple of minutes), open the run and download the
   **`vibe-launcher-apk`** artifact. Inside is `app-debug.apk`.

That's your launcher.

---

## 2. Put it on the TV (via adb)

1. On your computer, download **"SDK Platform Tools"** from Google
   (search "android platform tools"). It's a small zip — unzip it; it
   contains `adb`. No full SDK needed.
2. On the TV: **Settings → Device Preferences → About**, click **Build**
   7 times to unlock Developer options. Then in
   **Developer options**, turn on **Network debugging / USB debugging**.
3. Find the TV's IP: **Settings → Network → check network status**.
4. On your computer, in the platform-tools folder:
   ```
   adb connect <TV-IP>:5555
   adb install app-debug.apk
   ```
   (Accept the debugging prompt on the TV the first time.)
5. Press the **Home** button on the remote. The TV should ask which
   launcher to use — pick **Vibe Launcher** and set it as default.

To go back to the stock launcher later: Settings → Apps → see all apps →
disable or uninstall Vibe Launcher, or clear its "default" and re-pick.

---

## 3. Vibecode it — where to tweak

Everything lives in **`app/src/main/java/com/vibe/launcher/MainActivity.kt`**.
It's one short file. Ideas:

- **Search box:** the filter lives in the `filter()` function — e.g. change
  `.contains(q)` to `.startsWith(q)` for stricter matching.
- **Favourites:** hold **OK** on an app to pin it. The row logic is in
  `buildFavouritesRow()` / `toggleFavourite()`; choices are saved so they
  survive reboots.
- **Wallpaper:** `refreshWallpaperAsync()` fetches it. It picks one of Bing's
  recent daily images by day-of-year so it rotates automatically. Swap
  `_1920x1080.jpg` for `_UHD.jpg` for 4K, or change the feed URL to a
  different source. The last image is cached, so it shows instantly and
  still works offline.
- **Grid density:** change `numColumns = 5`.
- **Icon size:** change `dp(96)` in the adapter.
- **Sort differently:** change the `out.sortBy { ... }` line (e.g. most-recent,
  or hand-pin your favourites to the top).
- **Hide apps you never use:** skip them by package name in `loadApps()`.
- **Colours/background:** the gradient is in `res/drawable/banner.xml`;
  the background is set in `res/values/styles.xml`.

Every time you change a file in GitHub and commit, Actions rebuilds a fresh
APK automatically. Download it, `adb install -r app-debug.apk`, done.

---

## Notes

- The APK is **debug-signed**, which is why `adb install` works with no setup.
  It's fine for personal use on your own TV.
- `minSdk` is 21, so it runs on basically any Android TV from the last decade.
- If `Home` doesn't offer the launcher chooser, open
  Settings → Apps → Default apps → Home app, and pick Vibe Launcher.
