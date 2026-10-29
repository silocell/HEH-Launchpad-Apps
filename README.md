# Homework Launchpad apps

The Homework Launchpad as real apps, with Rocket Rush built for each one:

| Platform | Language | Rocket Rush |
|---|---|---|
| Android | Kotlin | native, light on phones, richer on tablets |
| iPhone / iPad | Swift (SwiftUI + SpriteKit) | native, light on iPhone, rich on iPad |
| Mac | Swift (same app, Mac Catalyst) | native, rich |
| Windows | C# (.NET Framework 4.8 + WebView2) | web, highest quality |
| Linux (deb, AppImage, snap) | C (GTK 3 + WebKitGTK) | web, medium quality |

Copyright © 2026 silocell. All rights reserved. See [LICENSE](LICENSE).

Builds for iPhone/iPad, Mac and Linux are made by GitHub Actions on every push and
land on the [`builds`](../../tree/builds) branch. Android and Windows are built locally
(Android needs the private signing key, which is never stored here).

## Installing

### iPhone / iPad (`HomeworkLaunchpad.ipa`, sideloading)
The IPA is unsigned, so it has to be signed with your own Apple ID when it's installed.
1. On a Windows PC or Mac, install **Sideloadly** (sideloadly.io) and iTunes/iCloud (Windows only, the non-Microsoft-Store versions).
2. Plug the iPad/iPhone in with a cable and tap **Trust** on it.
3. Open Sideloadly, drag in `HomeworkLaunchpad.ipa`, type your Apple ID and press **Start**.
   (Use a normal free Apple ID; Sideloadly sends it only to Apple.)
4. On the device: **Settings → Privacy & Security → Developer Mode → On** (iOS 16+; it restarts),
   then **Settings → General → VPN & Device Management →** your Apple ID **→ Trust**.
5. With a free Apple ID the app works for **7 days**; plug in and press Start again to renew it.
   AltStore (altstore.io) can renew it automatically over Wi-Fi.

### Mac (`HomeworkLaunchpad.dmg`)
Open the DMG and drag **Homework Launchpad** into Applications. The first time, macOS
blocks it because it isn't from the App Store: try to open it once, then go to
**System Settings → Privacy & Security** and press **Open Anyway**.

### Windows (`HomeworkLaunchpad.exe`)
Just run it. If SmartScreen says "Windows protected your PC", press **More info → Run anyway**.

### Android (`HomeworkLaunchpad.apk`)
Open the APK and allow "install unknown apps" for the app you opened it from.

### Ubuntu / Debian (`homework-launchpad_2.0_amd64.deb`)
```
sudo apt install ./homework-launchpad_2.0_amd64.deb
```
Works on Ubuntu 22.04+ and Debian 12+.

### Any Linux (`HomeworkLaunchpad-x86_64.AppImage`)
Needs WebKitGTK 4.1 (already there on most desktops; on Ubuntu `sudo apt install libwebkit2gtk-4.1-0`).
```
chmod +x HomeworkLaunchpad-x86_64.AppImage && ./HomeworkLaunchpad-x86_64.AppImage
```

### Snap (`homework-launchpad_2.0_amd64.snap`)
Until it's in the Snap Store it's an unsigned local snap:
```
sudo snap install --dangerous ./homework-launchpad_2.0_amd64.snap
```

## Signing in
Microsoft sign-in happens inside the app. Google doesn't allow sign-in inside apps, so
Google opens the normal browser and then hands you back to the app.
