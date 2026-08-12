#!/usr/bin/env bash
# Compiles and runs the cross-version demo with Maven, both with and without
# the agent.
#
#   JAVA_HOME=/path/to/jdk-28 ./run-demo.sh [debug]
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

: "${JAVA_HOME:?JAVA_HOME must point to a JDK 28 (or later) build}"
export JAVA_HOME

if [ "${1:-}" = "debug" ]; then
    EXTRA=(-Dauto-valhalla.debug=true)
else
    EXTRA=()
fi

mvn -q package

# jars follow Maven's artifactId-version convention; resolve them dynamically
find_jar() { ls "$1"/target/"$2"-*.jar 2>/dev/null | grep -vE -- '-(javadoc|sources)\.jar$' | head -n1; }
AGENT_JAR=$(find_jar auto-valhalla-agent auto-valhalla-agent)
DEMO5_JAR=$(find_jar auto-valhalla-demo5 auto-valhalla-demo5)
DEMO16_JAR=$(find_jar auto-valhalla-demo16 auto-valhalla-demo16)
ANNO_JAR=$(find_jar auto-valhalla-annotation auto-valhalla-annotation)

# the runner and the (JDK 5) annotation artifact, plus the demo5/demo16 jars
CP="auto-valhalla-demo-runner/target/classes:$DEMO5_JAR:$DEMO16_JAR:$ANNO_JAR"

echo
echo "==== run WITHOUT agent (identity classes) ===="
"$JAVA_HOME/bin/java" --enable-preview \
    -cp "$CP" demo.runner.Main

echo
echo "==== run WITH agent (annotation + includes selection, mode=yolo) ===="
"$JAVA_HOME/bin/java" --enable-preview \
    -javaagent:"$AGENT_JAR" \
    -Dauto-valhalla.includes=demo16,demo5 \
    -Dauto-valhalla.mode=yolo \
    "${EXTRA[@]}" \
    -cp "$CP" demo.runner.Main
