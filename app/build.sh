#!/usr/bin/env bash
# Build the Ride Relay companion APK with aapt2 + javac + d8 + apksigner.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-34/android.jar"
OUT="$ROOT/out"
BUILD="$ROOT/build"

for t in aapt2 d8 zipalign apksigner; do
  [ -x "$BT/$t" ] || { echo "missing $BT/$t"; exit 1; }
done
[ -f "$AJ" ] || { echo "missing android.jar at $AJ"; exit 1; }

rm -rf "$BUILD" "$OUT"
mkdir -p "$BUILD/classes" "$BUILD/gen" "$OUT"

echo "== aapt2 compile =="
if [ -d "$ROOT/res" ]; then
  "$BT/aapt2" compile --dir "$ROOT/res" -o "$BUILD/res.zip"
fi

echo "== aapt2 link =="
# Built with 'set --' rather than an array: macOS ships bash 3.2, where an
# empty array under 'set -u' is an unbound-variable error.
set -- \
  -o "$BUILD/base.apk" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -I "$AJ" \
  --java "$BUILD/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34
if [ -f "$BUILD/res.zip" ]; then
  set -- "$@" "$BUILD/res.zip"
fi
"$BT/aapt2" link "$@"

echo "== javac =="
SRCS=$(find "$ROOT/src" "$BUILD/gen" -name '*.java')
javac --release 17 -encoding UTF-8 \
  -classpath "$AJ" \
  -d "$BUILD/classes" \
  $SRCS

echo "== d8 =="
CLASSES=$(find "$BUILD/classes" -name '*.class')
"$BT/d8" --lib "$AJ" --min-api 24 --output "$BUILD" $CLASSES

echo "== package =="
cp "$BUILD/base.apk" "$BUILD/app.apk"
( cd "$BUILD" && zip -q -X app.apk classes.dex )

echo "== zipalign =="
"$BT/zipalign" -f 4 "$BUILD/app.apk" "$BUILD/app-aligned.apk"

echo "== sign =="
if [ ! -f "$ROOT/relay.jks" ]; then
  keytool -genkeypair -keystore "$ROOT/relay.jks" -alias relay \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Ride Relay, OU=dev, O=dev, L=x, S=x, C=IN"
fi
"$BT/apksigner" sign \
  --ks "$ROOT/relay.jks" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias relay \
  --out "$OUT/ride-relay.apk" \
  "$BUILD/app-aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/ride-relay.apk" | head -4
ls -la "$OUT/ride-relay.apk"
echo "OK -> $OUT/ride-relay.apk"
