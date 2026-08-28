#!/usr/bin/env bash
# Builds the project, runs all tests, and runs every verification the agent relies on:
#   1. full Maven build + tests (annotation tests on JDK 8, agent tests on JDK 28)
#   2. agent jar class-file version audit (shim = 49, real agent = 72,
#      annotation = 49, exactly one AutoValhallaAgent28)
#   3. JDK 5 safety: the agent must warn and return on an ancient JVM
#   4. JDK 28 agent-attach integration test (real transformation path)
#   5. end-to-end tests (with vs without the agent) (./test/run-tests.sh)
#   6. maven-plugin consumers: jdk28 demo on JDK 28, and the jdk8 consumer
#      built with Maven running on the JDK 8 in JAVA8_HOME
#
# Requires JAVA28_HOME, JAVA5_HOME, and JAVA8_HOME; JAVA_HOME is derived from
# JAVA28_HOME. jdk-setup.sh is used to auto-configure from the JDKs installed
# beside JAVA_HOME; otherwise they must be exported in the environment.
set -uo pipefail

cd "$(dirname "$0")"

source ./jdk-setup.sh

: "${JAVA28_HOME:?JAVA28_HOME must point to a JDK 28 build}"
: "${JAVA5_HOME:?JAVA5_HOME must point to a JDK 5 build for the shim/demos}"
: "${JAVA8_HOME:?JAVA8_HOME must point to a JDK 8 build for the jdk8 plugin consumer}"
export JAVA_HOME="$JAVA28_HOME"
export JAVA5_HOME
export JAVA8_HOME
export JAVA28_HOME

RUNNER5_CLASSES=test/test-jdk5/test-main-jdk5/target/classes
# agent / demo5 jars follow Maven's artifactId-version convention; resolved after the build
AGENT_JAR=""
DEMO5_JAR=""

PASS=0
FAIL=0
check() {
  if [ "$1" -eq 0 ]; then
    echo "  PASS: $2"
    PASS=$((PASS + 1))
  else
    echo "  FAIL: $2"
    FAIL=$((FAIL + 1))
  fi
}

# major version of a class file inside a jar
major_of() {
  local jar="$1" entry="$2" absjar tmp
  absjar=$(cd "$(dirname "$jar")" 2>/dev/null && pwd)/$(basename "$jar")
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN
  ( cd "$tmp" && "$JAVA_HOME/bin/jar" xf "$absjar" "$entry" >/dev/null 2>&1 )
  "$JAVA_HOME/bin/javap" -v "$tmp/$entry" 2>/dev/null | grep -oE "major version: [0-9]+" | grep -oE "[0-9]+"
  rm -rf "$tmp"
}

echo "== 1. build + tests =="
mvn install -Dauto-valhalla.build-script-running=true
RC=$?
check "$RC" "mvn install (annotation=JDK8, agent=JDK28)"
[ "$RC" -eq 0 ] || { echo "FATAL: mvn install failed; aborting."; exit 1; }

AGENT_JAR=$(ls auto-valhalla-agent/target/auto-valhalla-agent-*.jar 2>/dev/null | grep -vE -- '-(javadoc|sources)\.jar$' | head -n1)
ATTACH_JAR=$(ls auto-valhalla-agent-attach/target/auto-valhalla-agent-attach-*.jar 2>/dev/null | grep -vE -- '-(javadoc|sources)\.jar$' | head -n1)
DEMO5_JAR=$(ls test/test-jdk5/test-lib-jdk5/target/test-lib-jdk5-*.jar 2>/dev/null | grep -vE -- '-(javadoc|sources)\.jar$' | head -n1)

echo "== 2. agent jar class-file audit =="
SHIM=$(major_of "$AGENT_JAR" io/github/thunkware/auto/valhalla/AutoValhallaAgent.class)
REAL=$(major_of "$AGENT_JAR" io/github/thunkware/auto/valhalla/AutoValhallaAgent28.class)
ANNO=$(major_of "$AGENT_JAR" io/github/thunkware/auto/valhalla/api/AutoValhalla.class)
COUNT=$("$JAVA_HOME/bin/jar" tf "$AGENT_JAR" | grep -c "io/github/thunkware/auto/valhalla/AutoValhallaAgent28.class$")
[ "$SHIM" = "49" ]; check $? "shim is class-file 49 (JDK 5) [got $SHIM]"
[ "$REAL" = "72" ]; check $? "real agent is class-file 72 (JDK 28) [got $REAL]"
[ "$ANNO" = "49" ]; check $? "annotation is class-file 49 (JDK 5) [got $ANNO]"
[ "$COUNT" = "1" ]; check $? "exactly one AutoValhallaAgent28 (dummy excluded) [got $COUNT]"

echo "== 3. JDK 5 safety (agent must warn + return, app still runs) =="
OUT=$( "$JAVA5_HOME/bin/java" -javaagent:"$AGENT_JAR" -cp "$RUNNER5_CLASSES:$DEMO5_JAR:$ATTACH_JAR" demo5runner.Main 2>&1 )
RC=$?
echo "$OUT" | grep -q "unsupported JVM"; check $? "JDK 5 prints unsupported-JVM warning"
echo "$OUT" | grep -q "attach not supported"; check $? "JDK 5 attach entry point reports unsupported"
echo "$OUT" | grep -q "application executed without agent interference"; check $? "JDK 5 app runs to completion"
[ "$RC" = "0" ]; check $? "JDK 5 exit code 0 [got $RC]"

echo "== 3b. JDK 28 WITHOUT --enable-preview (agent must disable, not crash) =="
OUT28=$( "$JAVA_HOME/bin/java" -javaagent:"$AGENT_JAR" -cp "$RUNNER5_CLASSES:$DEMO5_JAR:$ATTACH_JAR" demo5runner.Main 2>&1 )
RC28=$?
echo "$OUT28" | grep -q "unsupported JVM"; check $? "JDK 28 (no preview) prints disable warning"
[ "$RC28" = "0" ]; check $? "JDK 28 (no preview) exit code 0 [got $RC28]"

echo "== 4. JDK 28 agent-attach integration test =="
mvn -pl test/test-jdk17/test-main-jdk17 test -Dauto-valhalla.build-script-running=true
check $? "demo-runner AgentAttachTest"

echo "== 5. end-to-end demo =="
./test/run-tests.sh
check $? "run-tests.sh (with and without agent)"

echo "== 6. maven-plugin end-to-end =="
PLUGIN_DEMO_JAR=$(ls test/test-maven-plugin-jdk28/target/test-maven-plugin-jdk28-*.jar 2>/dev/null | grep -vE -- '-(javadoc|sources)\.jar$' | head -n1)
[ -n "$PLUGIN_DEMO_JAR" ]; check $? "maven-plugin demo jar built [got $PLUGIN_DEMO_JAR]"
unzip -p "$PLUGIN_DEMO_JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -q "Multi-Release: true"; check $? "maven-plugin demo jar manifest is multi-release"
unzip -p "$PLUGIN_DEMO_JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -q "^Main-Class: demo.Main"; check $? "maven-plugin demo jar declares Main-Class"
"$JAVA_HOME/bin/jar" tf "$PLUGIN_DEMO_JAR" 2>/dev/null | grep -q "META-INF/versions/28/demo/Point.class"; check $? "maven-plugin demo jar carries value-class variants"
OUTPLUGIN=$( "$JAVA_HOME/bin/java" --enable-preview -jar "$PLUGIN_DEMO_JAR" 2>&1 )
RUNPLUGIN_RC=$?
echo "$OUTPLUGIN" | grep -q "Point.isValue()=true"; check $? "annotated demo.Point is a value class at runtime"
echo "$OUTPLUGIN" | grep -q "sum=7"; check $? "maven-plugin demo app runs from the multi-release jar"
echo "$OUTPLUGIN" | grep -q "OK: all classes are value classes"; check $? "maven-plugin demo Main force-checks the value classes"
[ "$RUNPLUGIN_RC" = "0" ]; check $? "maven-plugin demo Main exits 0 [got $RUNPLUGIN_RC]"

echo "== 6b. maven-plugin consumer on JDK 8 (test-maven-plugin-jdk8) =="
# test-maven-plugin-jdk8 excludes itself from the reactor and enforces Maven on
# [8,9), so this module is built standalone with JAVA_HOME="$JAVA8_HOME". The
# value-class javac still comes from the JDK 28 in JAVA28_HOME; capture the JDK
# 28 path first because bash expands assignment words left-to-right, so a bare
# JAVA_HOME="$JAVA8_HOME" JAVA28_HOME="$JAVA_HOME" mvn ... would already see the
# (now JDK-8) JAVA_HOME.
JDK28_CAPTURED="${JAVA28_HOME:-$JAVA_HOME}"
JAVA_HOME="$JAVA8_HOME" JAVA28_HOME="$JDK28_CAPTURED" \
    mvn -f test/test-maven-plugin-jdk8/pom.xml clean package -Dauto-valhalla.build-script-running=true
check $? "test-maven-plugin-jdk8 builds (Maven on JDK 8, value-class javac from JAVA28_HOME)"

JDK8_BASE=test/test-maven-plugin-jdk8/target/classes/demo/Point.class
JDK8_VALUE=test/test-maven-plugin-jdk8/target/classes/META-INF/versions/28/demo/Point.class
[ -f "$JDK8_VALUE" ]; check $? "jdk8 module produced the versioned value-class variant"
"$JAVA_HOME/bin/javap" -v "$JDK8_VALUE" 2>/dev/null | grep -q "value class demo.Point"; check $? "jdk8 versioned Point is a value class"
[ "$("$JAVA_HOME/bin/javap" -v "$JDK8_VALUE" 2>/dev/null | grep -oE "major version: [0-9]+" | grep -oE "[0-9]+")" = "72" ]; check $? "jdk8 versioned Point is class-file 72 (JDK 28)"
[ "$("$JAVA_HOME/bin/javap" -v "$JDK8_BASE" 2>/dev/null | grep -oE "major version: [0-9]+" | grep -oE "[0-9]+")" = "52" ]; check $? "jdk8 base Point stays class-file 52 (Java 8)"

echo
echo "==================== RESULT ===================="
echo "  passed: $PASS"
echo "  failed: $FAIL"
echo "================================================"
[ "$FAIL" -eq 0 ]
