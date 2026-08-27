#!/usr/bin/env bash
# Compiles and runs the cross-version demo with Maven, both with and without
# the agent.
#
#   JAVA_HOME=/path/to/jdk-28 ./test/run-tests.sh [debug]
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

: "${JAVA_HOME:?JAVA_HOME must point to a JDK 28 (or later) build}"
export JAVA_HOME

if [ "${1:-}" = "debug" ]; then
    # the agent has no `debug` flag; raise its log level instead
    EXTRA=(-Dlogging.level.root=DEBUG)
else
    EXTRA=()
fi

mvn -q package -Dauto-valhalla.build-script-running=true

# jars follow Maven's artifactId-version convention; resolve them dynamically
find_jar() { ls "$1"/target/"$2"-*.jar 2>/dev/null | grep -vE -- '-(javadoc|sources)\.jar$' | head -n1; }
AGENT_JAR=$(find_jar auto-valhalla-agent auto-valhalla-agent)
DEMO5_JAR=$(find_jar test/test-jdk5/test-lib-jdk5 test-lib-jdk5)
DEMO17_JAR=$(find_jar test/test-jdk17/test-lib-jdk17 test-lib-jdk17)
ANNO_JAR=$(find_jar auto-valhalla-api auto-valhalla-api)

# the runner and the (JDK 5) annotation artifact, plus the demo5/demo17 jars
CP="test/test-jdk17/test-main-jdk17/target/classes:$DEMO5_JAR:$DEMO17_JAR:$ANNO_JAR"

echo
echo "==== run WITHOUT agent (identity classes) ===="
"$JAVA_HOME/bin/java" --enable-preview \
    -Ddemo.expect=identity \
    -cp "$CP" demo.runner.Main

echo
echo "==== run WITH agent (annotation + includes selection, includes-mode=yolo) ===="
"$JAVA_HOME/bin/java" --enable-preview \
    -javaagent:"$AGENT_JAR" \
    -Ddemo.expect=value \
    -Dauto-valhalla.includes=demo17.includes.,demo5.includes. \
    -Dauto-valhalla.annotation-mode=yolo \
    -Dauto-valhalla.includes-mode=yolo \
    "${EXTRA[@]}" \
    -cp "$CP" demo.runner.Main
