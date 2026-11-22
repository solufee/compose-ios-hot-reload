#!/bin/bash
# Regression guard for JIT perf maps (Issue #108). libKaldo appends to /tmp/perf-<pid>.map (about 70 MB per launch)
# whenever KALDO_PERF_MAP is set; a regression run that leaves such files behind fills the disk over a day.
#
#   perf-map-guard.sh start <state-file> [<log>]   remember the perf maps that exist now
#   perf-map-guard.sh check <state-file> [<log>]   exit 1 if perf maps were added since start (listed, with the
#                                                  process if it still runs); exit 0 otherwise
# Both lines are appended to <log> (e.g. runner.log) when given. Files that existed at start are never reported.
set -u
cmd=${1:?start|check}; state=${2:?state file}; log=${3:-}
DIR=${PERF_MAP_DIR:-/tmp}
say() { echo "$*"; [ -n "$log" ] && echo "$(date '+%T') $*" >> "$log"; return 0; }
list() { ( cd "$DIR" && ls -1 perf-*.map 2>/dev/null | LC_ALL=C sort ); }
case $cmd in
  start)
    list > "$state"
    say "perf-map guard: start, $(wc -l < "$state" | tr -d ' ') existing $DIR/perf-*.map"
    ;;
  check)
    [ -f "$state" ] || { say "perf-map guard: FAIL, no start state $state"; exit 1; }
    new=$(list | LC_ALL=C comm -13 "$state" -)
    if [ -z "$new" ]; then
      say "perf-map guard: PASS, no new $DIR/perf-*.map"
      exit 0
    fi
    n=$(echo "$new" | wc -l | tr -d ' ')
    say "perf-map guard: FAIL, $n new $DIR/perf-*.map left behind:"
    for f in $new; do
      pid=${f#perf-}; pid=${pid%.map}
      size=$(du -k "$DIR/$f" 2>/dev/null | cut -f1)
      proc=$(ps -o comm= -p "$pid" 2>/dev/null | sed 's#.*/##')
      say "  $f ${size:-?} KB ${proc:+(process $pid still runs: $proc)}"
    done
    exit 1
    ;;
  *) echo "usage: perf-map-guard.sh start|check <state-file> [<log>]" >&2; exit 2 ;;
esac
