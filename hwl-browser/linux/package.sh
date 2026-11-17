#!/bin/sh
# Builds HWL Browser for Linux and packs it as a .deb and an AppImage.
# Run on Ubuntu 22.04 (the oldest supported), so it works on newer systems too.
set -e
cd "$(dirname "$0")"
VER=1.0
OUT=${OUT:-../out}
mkdir -p "$OUT" build
gcc main.c $(pkg-config --cflags --libs webkit2gtk-4.1 libsoup-3.0) -O2 -Wall -o hwl-browser
strip hwl-browser
ID=org.civilinitiatives.hwlbrowser

# ---- .deb (Ubuntu 22.04+, Debian 12+) ----
D=build/deb
rm -rf "$D"
install -Dm755 hwl-browser "$D/usr/bin/hwl-browser"
install -Dm644 ../app/src/main/assets/home.html "$D/usr/share/hwl-browser/home.html"
install -Dm644 data/$ID.desktop "$D/usr/share/applications/$ID.desktop"
install -Dm644 data/$ID.png "$D/usr/share/icons/hicolor/256x256/apps/$ID.png"
mkdir -p "$D/DEBIAN"
cat > "$D/DEBIAN/control" <<CTL
Package: hwl-browser
Version: $VER
Section: web
Priority: optional
Architecture: amd64
Depends: libwebkit2gtk-4.1-0 (>= 2.36), libgtk-3-0, libsoup-3.0-0
Maintainer: silocell <noreply@civilinitiatives.org>
Homepage: https://civilinitiatives.org/
Description: HWL Browser
 A school-safe web browser. Every website is checked by the Homework
 Launchpad's AI safety check before it opens.
CTL
cat > "$D/DEBIAN/postinst" <<'POST'
#!/bin/sh
update-desktop-database -q /usr/share/applications 2>/dev/null || true
gtk-update-icon-cache -q /usr/share/icons/hicolor 2>/dev/null || true
POST
chmod 755 "$D/DEBIAN/postinst"
dpkg-deb --root-owner-group --build "$D" "$OUT/hwl-browser_${VER}_amd64.deb"

# ---- AppImage (uses the system's WebKitGTK 4.1) ----
A=build/AppDir
rm -rf "$A"
install -Dm755 hwl-browser "$A/usr/bin/hwl-browser"
install -Dm644 ../app/src/main/assets/home.html "$A/usr/share/hwl-browser/home.html"
cp data/$ID.desktop data/$ID.png "$A/"
cp data/$ID.png "$A/.DirIcon"
printf '#!/bin/sh\nHERE="$(dirname "$(readlink -f "$0")")"\nexec "$HERE/usr/bin/hwl-browser" "$@"\n' > "$A/AppRun"
chmod 755 "$A/AppRun"
if [ ! -x build/appimagetool ]; then
  curl -sSfL -o build/appimagetool https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
  chmod +x build/appimagetool
fi
ARCH=x86_64 build/appimagetool --appimage-extract-and-run "$A" "$OUT/HWLBrowser-x86_64.AppImage"
ls -la "$OUT"
