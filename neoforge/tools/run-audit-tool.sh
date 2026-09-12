#!/usr/bin/env bash
#
# Compile and run one audit/preview program from tools/ against the current production classes.
#
#   ./tools/run-audit-tool.sh --list
#   ./tools/run-audit-tool.sh --compile-all
#   ./tools/run-audit-tool.sh TerrainPreview
#   ./tools/run-audit-tool.sh PlanningBenchmark <profile.json> <out-dir> <seed>
#
# Layout: classes go to build/audit-tools/classes, a directory no source set or JAR task reads, so
# the audit programs never enter the production artifact. The classpath is the one Gradle already
# knows: ./gradlew -I tools/planning-benchmark.gradle writePlanningBenchmarkClasspath writes
# build/planning-benchmark-classpath.txt from sourceSets.main.output and compileClasspath.
#
# Environment:
#   AWG_TOOL_MEM       JVM heap for the tool (default 2g)
#   AWG_TOOL_JAVA_OPTS extra JVM options
#   AWG_GRADLE_ARGS    arguments for the classpath task (default --offline)
#
# Each program documents its own parameters in its header comment; tools/README.md collects them,
# including the ones that still carry a hardcoded path or read a resource that no longer exists.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"
CLASSES="$PROJECT/build/audit-tools/classes"
CLASSPATH_FILE="$PROJECT/build/planning-benchmark-classpath.txt"
MEM="${AWG_TOOL_MEM:-2g}"
JAVA_OPTS="${AWG_TOOL_JAVA_OPTS:-}"
GRADLE_ARGS="${AWG_GRADLE_ARGS:---offline}"

usage() {
  sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

list_tools() {
  for file in "$HERE"/*.java; do basename "$file" .java; done | sort
}

ensure_classpath() {
  if [ ! -f "$CLASSPATH_FILE" ]; then
    echo "resolving the production classpath..." >&2
    ( cd "$PROJECT" && ./gradlew -q -I tools/planning-benchmark.gradle writePlanningBenchmarkClasspath $GRADLE_ARGS ) >&2 || {
      echo "could not write $CLASSPATH_FILE" >&2; exit 3; }
  fi
  cat "$CLASSPATH_FILE"
}

compile_tool() {
  local tool="$1" classpath="$2"
  if [ ! -f "$HERE/$tool.java" ]; then
    echo "no such tool: $tool (see --list)" >&2
    return 2
  fi
  mkdir -p "$CLASSES"
  # -sourcepath lets a tool pull in another tool it references, without compiling them into main.
  javac -encoding UTF-8 -nowarn -d "$CLASSES" -cp "$classpath" -sourcepath "$HERE" "$HERE/$tool.java"
}

case "${1:-}" in
  ""|-h|--help)
    usage
    exit 0
    ;;
  --list)
    list_tools
    exit 0
    ;;
  --compile-all)
    classpath="$(ensure_classpath)" || exit $?
    failed=0
    while read -r tool; do
      if compile_tool "$tool" "$classpath" >/tmp/awg-compile-"$tool".log 2>&1; then
        echo "ok      $tool"
      else
        echo "FAILED  $tool  (see /tmp/awg-compile-$tool.log)"
        failed=$((failed + 1))
      fi
    done < <(list_tools)
    echo "---"
    echo "$failed of $(list_tools | wc -l) tools do not compile against the current classes"
    exit $(( failed > 0 ? 1 : 0 ))
    ;;
esac

tool="$1"; shift
classpath="$(ensure_classpath)" || exit $?
compile_tool "$tool" "$classpath" || { echo "compile failed: $tool" >&2; exit 4; }
cd "$PROJECT" || exit 1
# shellcheck disable=SC2086
exec java -Xmx"$MEM" $JAVA_OPTS -cp "$CLASSES:$classpath" "$tool" "$@"
