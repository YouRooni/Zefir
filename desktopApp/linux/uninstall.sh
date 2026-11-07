#!/bin/sh
set -eu
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
rm -f "$DATA/applications/zefir.desktop" "$DATA/icons/hicolor/256x256/apps/zefir.png"
echo "Zefir shortcut removed"
