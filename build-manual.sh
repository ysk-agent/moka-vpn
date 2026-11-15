#!/bin/bash
# Manual APK build pipeline for moka-vpn (no Gradle, no CI).
#
# Why: Gradle cannot run on this VM (JVM loopback TCP is intercepted,
# so the Gradle client <-> daemon IPC fails), and Moriya directed on
# 2026-11-14 that getting a working VPN matters more than CI. Every
# tool below is a single in-process JVM or native binary:
#   aapt2 compile+link -> kotlinc -> d8 -> zip -> zipalign -> apksigner
# Toolchain pieces live under ~/workspace/toolchains/ (fetched with
# curl), the same arrangement as ~/workspace/phone-node/build-manual.sh.
#
# Inputs:
#   app/libs/libtailscale.aar  (gitignored) — built once per pinned
#     upstream ref and cached as the GitHub Release asset
#     libtailscale-<shortsha>; its classes.jar is the compile/dex
#     classpath for the libtailscale bindings and its
#     jni/arm64-v8a/libgojni.so is packaged (arm64 only, like CI).
#   signing/moka-vpn.keystore + signing/credentials.txt — the pinned
#     project key (gitignored), the same key CI uses, so manual and CI
#     builds overwrite-install over each other.
#
# Output: dist/mokavpn.apk
set -e
cd "$(dirname "$0")"

export JAVA_HOME="$HOME/workspace/toolchains/jdk-21"
export ANDROID_HOME="$HOME/workspace/toolchains/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"

BT="$ANDROID_HOME/build-tools/35.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
KOTLIN_STDLIB="$HOME/workspace/toolchains/kotlinc/lib/kotlin-stdlib.jar"
KOTLINC="$HOME/workspace/toolchains/kotlinc/bin/kotlinc"
KEYSTORE="$PWD/signing/moka-vpn.keystore"
AAR="$PWD/app/libs/libtailscale.aar"
OUT="$PWD/build-manual"
DIST="$PWD/dist"

[ -f "$AAR" ] || { echo "missing $AAR (fetch the libtailscale-<shortsha> release asset)" >&2; exit 1; }
[ -f "$KEYSTORE" ] || { echo "missing $KEYSTORE" >&2; exit 1; }

# Parse (never source) the signing credentials file.
STORE_PASS=$(sed -n 's/^store_pass: //p' signing/credentials.txt)
KEY_PASS=$(sed -n 's/^key_pass: //p' signing/credentials.txt)
KEY_ALIAS=$(sed -n 's/^key_alias: //p' signing/credentials.txt)
[ -n "$STORE_PASS" ] && [ -n "$KEY_PASS" ] && [ -n "$KEY_ALIAS" ] \
  || { echo "could not parse signing/credentials.txt" >&2; exit 1; }

echo "=== preparing ==="
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/dex" "$OUT/aar" "$DIST"
(cd "$OUT/aar" && unzip -o -q "$AAR" classes.jar "jni/arm64-v8a/libgojni.so")
AAR_CLASSES="$OUT/aar/classes.jar"

echo "=== aapt2 compile + link ==="
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 29 --target-sdk-version 34 \
  --version-code 8 --version-name 1.3.0 \
  --java "$OUT/gen" \
  "$OUT/res.zip"

echo "=== kotlinc ==="
SOURCES=$(find app/src/main/java -name '*.kt'; find "$OUT/gen" -name '*.java')
# shellcheck disable=SC2086
"$KOTLINC" -jvm-target 17 \
  -classpath "$ANDROID_JAR:$AAR_CLASSES" \
  -d "$OUT/classes.jar" $SOURCES

echo "=== d8 ==="
"$BT/d8" --lib "$ANDROID_JAR" --min-api 29 \
  --output "$OUT/dex" "$OUT/classes.jar" "$KOTLIN_STDLIB" "$AAR_CLASSES"

echo "=== package ==="
cp "$OUT/base.apk" "$OUT/mokavpn.apk"
(cd "$OUT/dex" && zip -q "../mokavpn.apk" classes.dex)
mkdir -p "$OUT/libstaging/lib/arm64-v8a"
cp "$OUT/aar/jni/arm64-v8a/libgojni.so" "$OUT/libstaging/lib/arm64-v8a/"
(cd "$OUT/libstaging" && zip -q -0 -r "$OUT/mokavpn.apk" lib)

"$BT/zipalign" -p -f 4 "$OUT/mokavpn.apk" "$OUT/mokavpn-aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" \
  --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KEY_ALIAS" \
  --out "$DIST/mokavpn.apk" "$OUT/mokavpn-aligned.apk"
"$BT/apksigner" verify --print-certs "$DIST/mokavpn.apk" | grep -i "SHA-256" || true
echo "OK: $DIST/mokavpn.apk"
