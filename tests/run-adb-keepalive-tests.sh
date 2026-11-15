#!/bin/bash
# Plain-JVM tests for the ADB keepalive extension's pure decision
# logic (app/src/main/java/dev/moriya/vpn/extensions/AdbKeepaliveLogic.kt).
#
# Only the decision function is exercised — it has no Android
# dependencies by construction. The Settings.Global I/O in
# AdbKeepaliveExtension is a thin shell around it and is verified
# on device.
set -e
cd "$(dirname "$0")/.."

export JAVA_HOME="$HOME/workspace/toolchains/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="$HOME/workspace/toolchains/kotlinc/bin/kotlinc"
KOTLIN_STDLIB="$HOME/workspace/toolchains/kotlinc/lib/kotlin-stdlib.jar"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -jvm-target 17 \
  -classpath "$KOTLIN_STDLIB" \
  -d "$OUT/adb-keepalive-tests.jar" \
  app/src/main/java/dev/moriya/vpn/extensions/AdbKeepaliveLogic.kt \
  tests/adb-keepalive/AdbKeepaliveLogicTest.kt

java -cp "$OUT/adb-keepalive-tests.jar:$KOTLIN_STDLIB" AdbKeepaliveLogicTestKt
