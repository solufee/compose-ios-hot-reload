#!/bin/bash
# #70 follow-up: warm restart with the ready marker (M) vs the old rule (O, KONAN_HR_READY_MARKER=0), interleaved
# M O M O ..., each split into the #60 relaunch steps (parse60.py; frame_to_marker / marker_to_detected added for M).
# Usage (toolchain env sourced, app packaged once by the caller or here): warm-mo.sh <out-dir> [pairs]
set -u
OUT=$(mkdir -p "$1" && cd "$1" && pwd); N=${2:-3}
M=$(cd "$(dirname "$0")" && pwd); cd "$M/../.."
bash hotreload.sh package > "$OUT/package0.out" 2>&1 || { echo "package failed"; exit 1; }
: > "$OUT/steps.txt"
for i in $(seq 1 "$N"); do
  for mode in M O; do
    tag=$mode$i; rm -f "$OUT/stop"
    python3 "$M/watch-log.py" "$PWD/launch.log" "$OUT/$tag-timeline.txt" "$OUT/stop" & WP=$!
    if [ $mode = O ]; then export KONAN_HR_READY_MARKER=0; else unset KONAN_HR_READY_MARKER; fi
    RELAUNCH_TIMING_LOG="$OUT/$tag-shim.log" PATH="$M/shim:$PATH" bash hotreload.sh warm 2>&1 | python3 "$M/ts.py" > "$OUT/$tag-reload.out"
    sleep 1; touch "$OUT/stop"; wait $WP
    unset KONAN_HR_READY_MARKER
    python3 "$M/parse60.py" "$OUT/$tag" >> "$OUT/steps.txt"
    echo "$tag: $(grep -o 'WARM OK.*' "$OUT/$tag-reload.out" | sed 's/^[0-9.]*\t//')"
  done
done
for mode in M O; do echo "== $mode"; grep "^$mode" "$OUT/steps.txt" > "$OUT/steps-$mode.txt"; python3 "$M/parse60.py" --summary "$OUT/steps-$mode.txt"; done
