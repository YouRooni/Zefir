#!/bin/sh
set -eu
DIR=$(cd "$(dirname "$0")" && pwd)
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
ICONS="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/256x256/apps"
mkdir -p "$APPS" "$ICONS"
cp "$DIR/zefir.png" "$ICONS/zefir.png"
cat > "$APPS/zefir.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Zefir
Comment=Proxy client
Exec="$DIR/bin/Zefir" %u
Icon=zefir
Terminal=false
Categories=Network;
StartupWMClass=dev-rooni-zefir-MainKt
EOF
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$APPS" || true
echo "Zefir: $APPS/zefir.desktop"
