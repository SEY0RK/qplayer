#!/usr/bin/env bash
set -euo pipefail
APK="$1"; EXPECT="$2"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
APKSIGNER=$(find "$SDK/build-tools" -type f -name apksigner 2>/dev/null | sort | tail -1)
if [ -z "$APKSIGNER" ]; then APKSIGNER=$(command -v apksigner || true); fi
if [ -z "$APKSIGNER" ]; then echo "::error::apksigner not found"; exit 1; fi
LINE=$("$APKSIGNER" verify --print-certs "$APK" | grep -m1 'SHA-256 digest' || true)
GOT=$(printf '%s' "$LINE" | sed -E 's/.*SHA-256 digest: *//' | tr -d ' \r')
if [ "$GOT" != "$EXPECT" ]; then
  echo "::error::certificate drift: got '$GOT' expected '$EXPECT'"; exit 1
fi
echo "certificate OK: $GOT"
