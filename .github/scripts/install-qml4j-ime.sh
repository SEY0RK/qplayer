#!/usr/bin/env bash
set -euo pipefail
mvn -B -q -f engine/qml4j-ime/pom.xml -pl qml4j-core -am install -DskipTests -Dcheckstyle.skip=true -Dpmd.skip=true
mvn -B -q install:install-file -Dfile=engine/qml4j-ime/qml4j-core/target/qml4j-core-0.2.38.jar -DpomFile=engine/qml4j-ime/jitpack-gav-pom.xml
test -f "$HOME/.m2/repository/com/github/TIMER-err/qml4j/qml4j-core/v0.2.38/qml4j-core-v0.2.38.jar"
