#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
source "$PROJECT_DIR/scripts/core.env"
ROOT=${ROOT:-$PROJECT_DIR/tmp/core-build}
TARGETS=${TARGETS:-android/arm64,android/arm,android/amd64}

mkdir -p "$ROOT"
cd "$ROOT"

if [ ! -x "go/bin/go" ] || ! go/bin/go version | grep -q "go$GO_VERSION"; then
  rm -rf go
  curl -fsSL "https://go.dev/dl/go$GO_VERSION.linux-amd64.tar.gz" | tar xz
fi

if [ ! -d FlClash/.git ]; then
  git clone --quiet https://github.com/chen08209/FlClash FlClash
fi
cd FlClash
git fetch --quiet origin
git checkout --quiet --force "$FLCLASH_COMMIT"
git config submodule.core/Clash.Meta.url https://github.com/chen08209/Clash.Meta.git
git submodule update --init --recursive --quiet
cp "$PROJECT_DIR/scripts/core-patches/dial_pipe.go" core/dial_pipe.go
bash "$PROJECT_DIR/scripts/core-patches/reality-version.sh" core/Clash.Meta

export GOROOT="$ROOT/go" GOPATH="$ROOT/gopath" GOTOOLCHAIN=local
JAVA_HOME=${JAVA_HOME:?Set JAVA_HOME to a JDK 21 installation}
export PATH="$ROOT/go/bin:$JAVA_HOME/bin:$ROOT/tools:$PATH"
export ANDROID_HOME=${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK installation}
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$NDK_VERSION}

cfg() { sed -n "s/^$1:[[:space:]]*//p" build_config.yaml | tr -d "\"'" | head -1; }
TAGS=$(cfg tags)
LDFLAGS="$(cfg go_ldflags)"
CORE_VERSION=$(cfg core_version)

cd core
mkdir -p "$ROOT/tools" "$ROOT/out"
go build -o "$ROOT/tools/" golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
GODEBUG_DEFAULT=$(go list -f '{{.DefaultGODEBUG}}' ./cmd/core)

echo "tags=$TAGS version=$CORE_VERSION targets=$TARGETS"
if [ -z "${ONLY_WINDOWS:-}" ]; then
  time gomobile bind \
    -target="$TARGETS" \
    -androidapi=26 \
    -javapkg=dev.rooni.vpncore \
    -tags="$TAGS" \
    -ldflags="$LDFLAGS -X=github.com/metacubex/mihomo/constant.Version=$CORE_VERSION -X=runtime.godebugDefault=$GODEBUG_DEFAULT" \
    -o "$ROOT/out/vpncore.aar" \
    ./mobile
fi

GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -tags="$TAGS" \
  -ldflags="$LDFLAGS -X=github.com/metacubex/mihomo/constant.Version=$CORE_VERSION" \
  -o "$ROOT/out/vpncore-windows-amd64.exe" ./cmd/core

GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -tags="$TAGS"   -ldflags="$LDFLAGS -X=github.com/metacubex/mihomo/constant.Version=$CORE_VERSION"   -o "$ROOT/out/vpncore-linux-amd64" ./cmd/core

printf 'flclash=%s\nmihomo=%s\ngo=%s\n' \
  "$FLCLASH_COMMIT" "$(git -C Clash.Meta rev-parse HEAD)" "$GO_VERSION" > "$ROOT/out/core-revisions.txt"

SOURCE_DIR="$ROOT/source/FlClash"
mkdir -p "$SOURCE_DIR/core/Clash.Meta"
git -C "$ROOT/FlClash" archive HEAD | tar -x -C "$SOURCE_DIR"
git -C "$ROOT/FlClash/core/Clash.Meta" archive HEAD | tar -x -C "$SOURCE_DIR/core/Clash.Meta"
cp "$PROJECT_DIR/scripts/core-patches/dial_pipe.go" "$SOURCE_DIR/core/dial_pipe.go"
bash "$PROJECT_DIR/scripts/core-patches/reality-version.sh" "$SOURCE_DIR/core/Clash.Meta"
cp "$ROOT/out/core-revisions.txt" "$ROOT/source/"
(cd "$SOURCE_DIR/core" && go mod vendor)
tar -czf "$ROOT/out/core-source.tar.gz" -C "$ROOT/source" .

ls -la "$ROOT/out"
echo BUILD_DONE
