#!/usr/bin/env bash
#
# Full GameTest acceptance: the four groups in sequence, each in its own world directory.
#
#   ./tools/run-full-gametest.sh                     # run id = timestamp
#   RUN_ID=boundaries-a-01 ./tools/run-full-gametest.sh
#   ./tools/run-full-gametest.sh --group planning    # one group only, same reporting
#
# Why a script and not one Gradle invocation: NeoForge enables GameTest namespaces through a
# single system property, so the four groups cannot be combined in one server start (see
# tools/gametest-group.gradle). This runner starts them one after another, keeps a log per group,
# and turns "any group failed, discovered fewer methods than the inventory, or reported a
# failure" into a non-zero exit status.
#
# Coverage is checked against the method inventory in
# src/test/java/io/github/luoyan/adventureworldgen/GameTestInventoryTest.java:
#   default 10 + performance 6 + capacity 1 + planning 1 = 18 methods.
#
# A passing run means the methods were discovered and passed. It is not a performance verdict:
# the performance group reports its own timings in the log, and the planning group must use a
# fresh directory to measure first planning rather than a READY reload.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
REPORT_DIR="$PROJECT/build/gametest-full-$RUN_ID"
GRADLE_ARGS="${GRADLE_ARGS:---offline}"
# Per-group wall-clock cap. A game-test server spends minutes flushing chunks after the last test
# reports, and a fresh directory with several per-test worlds makes that flush longer still (an
# observed overworld flush was 6m14s of silence). The cap exists so a genuinely stuck server cannot
# block the whole acceptance; it is not a speed measurement and should stay well above the flush.
GROUP_TIMEOUT="${GROUP_TIMEOUT:-2700}"

# group:expected method count. The default group also enables the minecraft and adventureworldgen
# namespaces; neither currently contributes a test, which is why discovery must equal 10.
# The variable is NOT named GROUPS: that is a special bash array of the caller's group IDs and an
# assignment to it is silently ignored, which made this loop iterate over the user's gids instead.
GAMETEST_GROUPS=(
  "default:10"
  "performance:6"
  "capacity:1"
  "planning:1"
)

selected=()
while [ $# -gt 0 ]; do
  case "$1" in
    --group)
      [ $# -ge 2 ] || { echo "--group needs a value" >&2; exit 2; }
      selected+=("$2")
      shift 2
      ;;
    --group=*)
      selected+=("${1#--group=}")
      shift
      ;;
    -h|--help)
      sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

mkdir -p "$REPORT_DIR"
summary="$REPORT_DIR/summary.txt"
overall=0

log() { printf '%s\n' "$*" | tee -a "$summary"; }

log "AdventureWorldGen full GameTest acceptance"
log "run id      : $RUN_ID"
log "project     : $PROJECT"
log "reports     : $REPORT_DIR"
log "gradle args : $GRADLE_ARGS"
log "group cap   : $GROUP_TIMEOUT s (kills a server still flushing chunks after reporting its result)"
log ""

for entry in "${GAMETEST_GROUPS[@]}"; do
  group="${entry%%:*}"
  expected="${entry##*:}"
  if [ ${#selected[@]} -gt 0 ]; then
    keep=0
    for want in "${selected[@]}"; do [ "$want" = "$group" ] && keep=1; done
    [ "$keep" = 1 ] || continue
  fi

  world="run-full-$group-$RUN_ID"
  echo "=== group $group (expect $expected methods) -> $world ===" | tee -a "$summary"
  (
    cd "$PROJECT" || exit 1
    # shellcheck disable=SC2086
    timeout "$GROUP_TIMEOUT" ./gradlew runGameTestServer -I tools/gametest-group.gradle \
      -PgametestGroup="$group" -PterrainAuditWorld="$world" $GRADLE_ARGS
  ) >"$REPORT_DIR/$group.gradle.log" 2>&1
  gradle_status=$?
  timed_out=0
  [ "$gradle_status" -eq 124 ] && timed_out=1
  cp "$PROJECT/$world/logs/latest.log" "$REPORT_DIR/$group.server.log" 2>/dev/null

  namespaces="$(grep -o 'Enabled Gametest Namespaces: \[[^]]*\]' "$REPORT_DIR/$group.server.log" 2>/dev/null | tail -1)"
  discovered="$(grep -oE '[0-9]+ tests are now running' "$REPORT_DIR/$group.server.log" 2>/dev/null | tail -1 | grep -oE '^[0-9]+')"
  batches="$(grep -oE "Running test batch '[^']+' \([0-9]+ tests\)" "$REPORT_DIR/$group.server.log" 2>/dev/null | tail -1)"
  complete="$(grep -oE '[0-9]+ GAME TESTS COMPLETE' "$REPORT_DIR/$group.server.log" 2>/dev/null | tail -1 | grep -oE '^[0-9]+')"
  passed="$(grep -oE 'All [0-9]+ required tests passed' "$REPORT_DIR/$group.server.log" 2>/dev/null | tail -1 | grep -oE '[0-9]+')"
  failed_line="$(grep -oE '[0-9]+ GAME TESTS FAILED' "$REPORT_DIR/$group.server.log" 2>/dev/null | tail -1)"

  status="passed"
  notes=()
  if [ "$gradle_status" -ne 0 ] && [ "$timed_out" -eq 0 ]; then
    status="FAILED"
    notes+=("gradle exit $gradle_status")
  fi
  if [ -n "$failed_line" ]; then
    status="FAILED"
    notes+=("$failed_line")
  fi
  if [ "${discovered:-}" != "$expected" ]; then
    status="FAILED"
    notes+=("discovered ${discovered:-0}, expected $expected")
  fi
  if [ "${complete:-}" != "$expected" ]; then
    status="FAILED"
    notes+=("executed ${complete:-0}, expected $expected")
  fi
  if [ "${passed:-}" != "$expected" ]; then
    status="FAILED"
    notes+=("passed ${passed:-0}, expected $expected")
  fi
  if [ -z "${discovered:-}" ]; then
    status="FAILED"
    notes+=("the server log has no test discovery line; see $group.server.log")
  fi
  # A timed-out run is only acceptable when the log proves the group already finished: every method
  # was discovered, executed and passed, and nothing failed. Otherwise the timeout is the failure.
  if [ "$timed_out" -eq 1 ]; then
    if [ "$status" = "passed" ]; then
      notes+=("the cap of $GROUP_TIMEOUT s expired although the group had already reported its full"
              + " result; the time went to Minecraft's own chunk flush at shutdown, not to a test")
    else
      status="FAILED"
      notes+=("killed after $GROUP_TIMEOUT s without a complete result")
    fi
  fi

  {
    echo "  namespaces : ${namespaces:-<not found>}"
    echo "  batch      : ${batches:-<not found>}"
    echo "  discovered : ${discovered:-0} (registered inventory: $expected)"
    echo "  executed   : ${complete:-0}"
    echo "  passed     : ${passed:-0}"
    echo "  skipped    : 0 (GameTest has no skip state; every discovered method executes)"
    echo "  status     : $status"
    for note in "${notes[@]:-}"; do [ -n "$note" ] && echo "  note       : $note"; done
    echo "  world      : $world"
    echo "  logs       : $REPORT_DIR/$group.server.log"
    echo ""
  } | tee -a "$summary"

  [ "$status" = "passed" ] || overall=1
done

if [ "$overall" -eq 0 ]; then
  log "RESULT: every selected group discovered, executed and passed its full inventory."
else
  log "RESULT: at least one group did not cover its inventory; see the notes above."
fi
echo "summary: $summary"
exit "$overall"
