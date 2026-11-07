#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
source "$PROJECT_DIR/scripts/core.env"
ROOT=${ROOT:-$PROJECT_DIR/tmp/core-build}
OUT="$PROJECT_DIR/core/prebuilt"
mkdir -p "$OUT/jniLibs" "$OUT/geo" "$OUT/legal"

cp "$ROOT/out/vpncore-windows-amd64.exe" "$OUT/"
install -m 755 "$ROOT/out/vpncore-linux-amd64" "$OUT/"
unzip -jo "$ROOT/out/vpncore.aar" classes.jar -d "$OUT"
mv "$OUT/classes.jar" "$OUT/vpncore-classes.jar"
for abi in arm64-v8a armeabi-v7a x86_64; do
  mkdir -p "$OUT/jniLibs/$abi"
  unzip -jo "$ROOT/out/vpncore.aar" "jni/$abi/*.so" -d "$OUT/jniLibs/$abi"
  test -s "$OUT/jniLibs/$abi/libgojni.so"
done

BASE="https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/$GEODATA_COMMIT"
curl -fL --retry 3 "$BASE/geoip.metadb" -o "$OUT/geo/GEOIP.metadb"
curl -fL --retry 3 "$BASE/geosite.dat" -o "$OUT/geo/GEOSITE.dat"
printf '%s  %s\n' "$GEOIP_SHA256" "$OUT/geo/GEOIP.metadb" "$GEOSITE_SHA256" "$OUT/geo/GEOSITE.dat" | sha256sum -c -

cp "$ROOT/out/core-revisions.txt" "$OUT/legal/"
cp "$ROOT/FlClash/LICENSE" "$OUT/legal/FlClash-LICENSE"
cp "$ROOT/FlClash/core/Clash.Meta/LICENSE" "$OUT/legal/mihomo-LICENSE"
