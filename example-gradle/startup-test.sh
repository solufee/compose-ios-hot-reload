#!/bin/bash
# Cold-start stress: terminate + launch the installed app N times, count failures.
set -u
cd "$(dirname "$0")"
UDID=${UDID:-0B879832-98D9-49A5-AC00-8BAB579AFBB2}
N=${1:-30}
BUNDLE=com.example.composehr
OUT=${2:-startup-30.log}
PASS=0; FAIL=0
: > "$OUT"
for i in $(seq 1 "$N"); do
  pkill -f "ComposeHR.app/ComposeHR" 2>/dev/null
  sleep 0.5
  LOG=/tmp/start-$i.log; : > "$LOG"
  SIMCTL_CHILD_KONAN_ORC_RUNTIME_PATH="${ORC_RUNTIME:-$PWD/tools/liborc_rt_iossim_arm64.a}" \
  SIMCTL_CHILD_KALDO_PERF_MAP=1 \
  SIMCTL_CHILD_KALDO_SYNTH_EH_FRAME=${KALDO_SYNTH_EH_FRAME:-1} \
    nohup xcrun simctl launch --console-pty "$UDID" "$BUNDLE" > "$LOG" 2>&1 &
  LP=$!
  ok=0; t0=$(date +%s)
  for t in $(seq 1 150); do
    sleep 0.4
    if grep -q "RENDER_APP" "$LOG" 2>/dev/null; then ok=1; break; fi
    if ! pgrep -f "ComposeHR.app/ComposeHR" >/dev/null; then break; fi
  done
  dt=$(( $(date +%s) - t0 ))
  if [ "$ok" = 1 ]; then PASS=$((PASS+1)); echo "start[$i] OK ${dt}s" | tee -a "$OUT"
  else FAIL=$((FAIL+1)); echo "start[$i] FAIL ${dt}s alive=$(pgrep -f ComposeHR.app/ComposeHR >/dev/null && echo yes || echo no)" | tee -a "$OUT"; tail -3 "$LOG" >> "$OUT"; fi
  kill "$LP" 2>/dev/null
done
echo "=== startup: ok=$PASS/$N fail=$FAIL ===" | tee -a "$OUT"
pkill -f "ComposeHR.app/ComposeHR" 2>/dev/null
