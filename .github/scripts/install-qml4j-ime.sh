#!/usr/bin/env bash
# Install the patched qml4j 0.2.39 (in-field preedit + code-point-safe text
# shaping) into the local Maven repository under the JitPack coordinates used
# by the upstream pom, so no dependency declaration has to change.
set -euo pipefail

ENGINE=engine/qml4j-ime
VERSION=0.2.39
GAV_VERSION=v0.2.39

mvn -B -q -f "$ENGINE/pom.xml" -pl qml4j-core -am install \
  -Dmaven.test.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true

mvn -B -q install:install-file \
  -Dfile="$ENGINE/qml4j-core/target/qml4j-core-$VERSION.jar" \
  -DpomFile="$ENGINE/jitpack-gav-pom.xml"

JAR="$HOME/.m2/repository/com/github/TIMER-err/qml4j/qml4j-core/$GAV_VERSION/qml4j-core-$GAV_VERSION.jar"
test -f "$JAR"
echo "Patched qml4j installed: $JAR"
