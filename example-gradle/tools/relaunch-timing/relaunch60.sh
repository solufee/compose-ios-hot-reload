#!/bin/bash
# #60 stage 1 (measurement only): split the relaunch part of a warm restart into steps on the example app.
#   tools/relaunch-timing/relaunch60.sh <out dir> <ui|app> <N>      (toolchain env sourced; behaviour of hotreload.sh/run.sh unchanged)
set -u
OUT=$1; KIND=$2; N=$3; mkdir -p "$OUT"
M=$(cd "$(dirname "$0")" && pwd)
E=$(cd "$M/../.." && pwd); R=$(cd "$E/.." && pwd); cd "$E"
export UDID=${UDID:-0B879832-98D9-49A5-AC00-8BAB579AFBB2}
restore() { (cd $R && git checkout -- example-gradle/app/src/main/kotlin/main.kt example-gradle/ui/src/main/kotlin/ui.kt); }
trap restore EXIT
restore; bash ../daemon/daemon-start.sh --force >/dev/null 2>&1
bash hotreload.sh package > "$OUT/package0.out" 2>&1 || { echo "package failed"; exit 1; }
xcrun simctl terminate "$UDID" com.example.composehr 2>/dev/null; : > launch.log
bash -c "set -m; nohup bash '$PWD/run.sh' > '$OUT/run0.out' 2>&1 & echo \$! > '$OUT/run0.pid'"
for _ in $(seq 1 150); do grep -q '^EH OK' launch.log 2>/dev/null && grep -q RENDER_APP launch.log && break; sleep 0.5; done; sleep 3
for i in $(seq 1 "$N"); do
  f=ui/src/main/kotlin/ui.kt; [ "$KIND" = app ] && f=app/src/main/kotlin/main.kt
  printf '\nval relaunchProbe%s%s: Int = %s\n' "$KIND" "$i" "$i" >> "$f"
  [ "$KIND" = ui ] && bash hotreload.sh ui-klib > "$OUT/$KIND$i-uiklib.out" 2>&1
  rm -f "$OUT/stop" "$OUT/$KIND$i-shim.log"
  python3 "$M/watch-log.py" "$PWD/launch.log" "$OUT/$KIND$i-timeline.txt" "$OUT/stop" &
  WP=$!; sleep 0.2
  RELAUNCH_TIMING_LOG="$OUT/$KIND$i-shim.log" PATH="$M/shim:$PATH" bash hotreload.sh reload 2>&1 | python3 "$M/ts.py" > "$OUT/$KIND$i-reload.out"
  sleep 1; : > "$OUT/stop"; wait "$WP"
  python3 "$M/parse60.py" "$OUT/$KIND$i" | tee -a "$OUT/steps.txt"
done
python3 "$M/parse60.py" --summary "$OUT/steps.txt" | tee "$OUT/summary.txt"
