#!/usr/bin/env bash
# Auto-configures the JDK locations used by auto-valhalla by probing every JDK
# installed next to the primary one.
#
# Usage (source it so the exports affect your shell):
#   source jdk-setup.sh
#
# The primary JDK is $JAVA_HOME (defaults to $JAVA28_HOME, then to the newest
# JDK found under $JAVA_SIBLING_DIR / the sdkman candidates directory). Every
# sibling "$JAVA_HOME/../*/bin/javac" is probed with `javac -version` and the
# JAVA5_HOME, JAVA6_HOME, JAVA7_HOME, JAVA8_HOME, JAVA21_HOME, JAVA25_HOME
# and JAVA28_HOME variables are filled in from the matching JDKs:
#
#   JAVA5_HOME    the JDK 5 used to compile the JDK 5 shim/annotation/demo5
#                 modules and to verify the agent is safe to attach on a pre-Valhalla JVM
#   JAVA6_HOME    a JDK 6
#   JAVA7_HOME    a JDK 7
#   JAVA8_HOME    a JDK 8 used by build.sh to build the jdk8 plugin consumer
#   JAVA21_HOME   a JDK 21 (handy for cross-version checks)
#   JAVA25_HOME   a JDK 25 (handy for cross-version checks)
#   JAVA28_HOME   a JDK 28 for the plugin's value-class compilation when
#                 Maven itself runs on an older JDK
#
# Variables already set in your environment are left untouched.

# reports the numeric feature version of a JDK (5 for "javac 1.5.0_22",
# 8 for "javac 1.8.0_492", 21 for "javac 21.0.11-tem", ...); 0 when unparseable
jdk_feature() {
  local out
  out=$("$1/bin/javac" -version 2>&1)
  out=${out%%$'\n'*}
  out=${out#javac }
  case "$out" in
    1.*) out=${out#1.} ;;
  esac
  out=${out%%[-+.]*}
  case "$out" in
    '' | *[!0-9]*) echo 0 ;;
    *) echo "$((10#$out))" ;;
  esac
}

# prints the directory under $1 with the newest JDK feature version, or nothing
newest_jdk() {
  local dir="" best=0 d v
  for d in "$1"/*; do
    [ -x "$d/bin/javac" ] || continue
    v=$(jdk_feature "$d")
    if [ "$v" -ge "$best" ]; then
      best=$v
      dir=$d
    fi
  done
  echo "$dir"
}

# --- resolve the primary JDK (JDK 28 or later) ---
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -n "${JAVA28_HOME:-}" ]; then
    export JAVA_HOME="$JAVA28_HOME"
  else
    for base in "${JAVA_SIBLING_DIR:-}" "$HOME/.sdkman/candidates/java"; do
      if [ -n "$base" ] && [ -d "$base" ]; then
        found=$(newest_jdk "$base")
        if [ -n "$found" ]; then
          export JAVA_HOME="$found"
          break
        fi
      fi
    done
  fi
fi

echo "Detecting JDK versions ..." >&2

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
  echo "jdk-setup.sh: no usable JDK found; set JAVA_HOME (JDK 28+) explicitly" >&2
  return 1 2>/dev/null || exit 1
fi
export JAVA_HOME

# --- auto-discovery: probe every sibling of the primary JDK ---
siblings="$(dirname "$JAVA_HOME")"
if [ -d "$siblings" ]; then
  for d in "$siblings"/*; do
    echo "Probing $d" >&2
    [ -x "$d/bin/javac" ] || continue
    case "$(jdk_feature "$d")" in
      5) export JAVA5_HOME="${JAVA5_HOME:-$d}" ;;
      6) export JAVA6_HOME="${JAVA6_HOME:-$d}" ;;
      7) export JAVA7_HOME="${JAVA7_HOME:-$d}" ;;
      8) export JAVA8_HOME="${JAVA8_HOME:-$d}" ;;
      21) export JAVA21_HOME="${JAVA21_HOME:-$d}" ;;
      25) export JAVA25_HOME="${JAVA25_HOME:-$d}" ;;
      28) export JAVA28_HOME="${JAVA28_HOME:-$d}" ;;
    esac
  done
  echo "" >&2
fi

# if the primary JDK itself is post-Valhalla (28+), it is the fallback JAVA28_HOME
if [ -z "${JAVA28_HOME:-}" ] && [ "$(jdk_feature "$JAVA_HOME")" -ge 28 ]; then
  export JAVA28_HOME="$JAVA_HOME"
fi

echo "JAVA_HOME=$JAVA_HOME"
echo "JAVA5_HOME=${JAVA5_HOME:-}"
echo "JAVA6_HOME=${JAVA6_HOME:-}"
echo "JAVA7_HOME=${JAVA7_HOME:-}"
echo "JAVA8_HOME=${JAVA8_HOME:-}"
echo "JAVA21_HOME=${JAVA21_HOME:-}"
echo "JAVA25_HOME=${JAVA25_HOME:-}"
echo "JAVA28_HOME=${JAVA28_HOME:-}"
echo "" >&2
