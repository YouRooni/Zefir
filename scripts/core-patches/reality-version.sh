#!/usr/bin/env bash
set -euo pipefail

FILE="$1/component/tls/reality.go"
sed -i \
  -e 's/hello\.SessionId\[0\] = 1$/hello.SessionId[0] = 26/' \
  -e 's/hello\.SessionId\[1\] = 8$/hello.SessionId[1] = 10/' \
  -e 's/hello\.SessionId\[2\] = 2$/hello.SessionId[2] = 31/' \
  "$FILE"
grep -q 'hello\.SessionId\[0\] = 26$' "$FILE"
grep -q 'hello\.SessionId\[1\] = 10$' "$FILE"
grep -q 'hello\.SessionId\[2\] = 31$' "$FILE"
