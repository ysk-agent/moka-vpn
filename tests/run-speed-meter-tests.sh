#!/bin/bash
# Plain-JVM tests for the speed meter extension's pure rate/format
# math (app/src/main/java/dev/moriya/vpn/extensions/SpeedMeterLogic.kt).
#
# Only the pure functions and the Sampler are exercised — they
# have no Android dependencies by construction. The TrafficStats
# I/O, notification rendering and screen handling in
# SpeedMeterExtension are a thin shell and are verified on device.
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
  -d "$OUT/speed-meter-tests.jar" \
  app/src/main/java/dev/moriya/vpn/extensions/SpeedMeterLogic.kt \
  tests/speed-meter/SpeedMeterLogicTest.kt

java -cp "$OUT/speed-meter-tests.jar:$KOTLIN_STDLIB" SpeedMeterLogicTestKt
