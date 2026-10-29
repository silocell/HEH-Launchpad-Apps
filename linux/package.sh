#!/bin/sh
# Builds the Linux app and packs it as a .deb and an AppImage.
# Run on Ubuntu 22.04 (the oldest supported), so it works on newer systems too.
set -e
cd "$(dirname "$0")"
VER=2.0
OUT=${OUT:-../out}
mkdir -p "$OUT"
gcc main.c $(pkg-config --cflags --libs webkit2gtk-4.1) -O2 -o homework-launchpad
strip homework-launchpad

# ---- .deb (Ubuntu 22.04+, Debian 12+) ----
D=build/deb
rm -rf "$D"
install -Dm755 homework-launchpad "$D/usr/bin/homework-launchpad"
install -Dm644 data/org.civilinitiatives.launchpad.desktop "$D/usr/share/applications/org.civilinitiatives.launchpad.desktop"
install -Dm644 data/org.civilinitiatives.launchpad.png "$D/usr/share/icons/hicolor/256x256/apps/org.civilinitiatives.launchpad.png"
mkdir -p "$D/DEBIAN"
cat > "$D/DEBIAN/control" <<CTL
Package: homework-launchpad
Version: $VER
Section: education
Priority: optional
Architecture: amd64
Depends: libwebkit2gtk-4.1-0 (>= 2.36), libgtk-3-0
Maintainer: silocell <noreply@civilinitiatives.org>
Homepage: https://civilinitiatives.org/
Description: Homework Launchpad
 Your homework, learning games, shop and Rocket Rush in its own window.
CTL
cat > "$D/DEBIAN/postinst" <<'POST'
#!/bin/sh
update-desktop-database -q /usr/share/applications 2>/dev/null || true
gtk-update-icon-cache -q /usr/share/icons/hicolor 2>/dev/null || true
POST
chmod 755 "$D/DEBIAN/postinst"
dpkg-deb --root-owner-group --build "$D" "$OUT/homework-launchpad_${VER}_amd64.deb"

# ---- AppImage (uses the system's WebKitGTK 4.1) ----
A=build/AppDir
rm -rf "$A"
install -Dm755 homework-launchpad "$A/usr/bin/homework-launchpad"
cp data/org.civilinitiatives.launchpad.desktop "$A/"
cp data/org.civilinitiatives.launchpad.png "$A/"
cp data/org.civilinitiatives.launchpad.png "$A/.DirIcon"
printf '#!/bin/sh\nHERE="$(dirname "$(readlink -f "$0")")"\nexec "$HERE/usr/bin/homework-launchpad" "$@"\n' > "$A/AppRun"
chmod 755 "$A/AppRun"
if [ ! -x build/appimagetool ]; then
  curl -sSfL -o build/appimagetool https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
  chmod +x build/appimagetool
fi
ARCH=x86_64 build/appimagetool --appimage-extract-and-run "$A" "$OUT/HomeworkLaunchpad-x86_64.AppImage"
ls -la "$OUT"
