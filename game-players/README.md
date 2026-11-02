# Launchpad game players

The Game Maker's "Download as app" uses these: ready-made players that the
gamemaker edge function adds one game to when someone downloads it.

- `windows/` - LaunchpadGame.exe (.NET Framework 4.8 + WebView2). The game goes on the
  end of the EXE: `[exe][game JSON][length, 4 bytes LE]["HLGAME01"]`.
- `android/` - LaunchpadGames.apk (Kotlin, WebView). The game goes in the APK's zip
  comment: `"HLGAME01" + base64(gzip(game JSON))`. Signed with v1 only (targetSdk 29)
  so the comment isn't covered by the signature. One app holds every game downloaded.

Built copies live at sandbox.civilinitiatives.org/players/. The Android signing key is
kept outside this repo (`LG_KEYS` points at its keystore.properties).
Every game runs with the same lock as on the Launchpad: no internet access at all.
