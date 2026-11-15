#!/bin/bash
# Plain-JVM tests for the proxy extension's pure protocol logic
# (app/src/main/java/dev/moriya/vpn/extensions/ProxyProtocol.kt).
#
# Only pure functions are exercised — no sockets — because JVM TCP
# is intercepted on this VM (the same fact that keeps Gradle off
# it). ProxyServerCore's socket/transit behaviour is therefore not
# covered here; it is verified on device.
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
  -d "$OUT/proxy-tests.jar" \
  app/src/main/java/dev/moriya/vpn/extensions/ProxyProtocol.kt \
  tests/proxy/ProxyProtocolTest.kt

java -cp "$OUT/proxy-tests.jar:$KOTLIN_STDLIB" ProxyProtocolTestKt
