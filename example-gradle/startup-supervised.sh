#!/bin/bash
# 30 cold starts through run.sh (which retries on the intermittent ORC crash).
set -u
cd "$(dirname "$0")"
UDID=${UDID:-0B879832-98D9-49A5-AC00-8BAB579AFBB2}
N=${1:-30}
OUT=${2:-startup-supervised.log}
PASS=0; FAIL=0; EXTRA=0; EHOK=0
: > "$OUT"
for i in $(seq 1 "$N"); do
  pkill -f "Devices/$UDID/.*ComposeHR.app/ComposeHR" 2>/dev/null; sleep 0.3
  L=/tmp/sup-$i.log; : > "$L"
  SKIP_INSTALL=${SKIP_INSTALL:-1} LAUNCH_LOG=$L ./run.sh > /tmp/sup-out-$i.log 2>&1 &
  RP=$!
  ok=0
  for t in $(seq 1 100); do
    sleep 0.4
    grep -q "started (attempt" /tmp/sup-out-$i.log 2>/dev/null && { ok=1; break; }
    grep -q "EH probe failed" /tmp/sup-out-$i.log 2>/dev/null && break
    kill -0 $RP 2>/dev/null || break
  done
  attempts=$(grep -c "died before first frame" /tmp/sup-out-$i.log 2>/dev/null)
  eh=$(grep -m1 "^EH OK" "$L" 2>/dev/null | tr -d "\r")
  [ -n "$eh" ] && EHOK=$((EHOK+1))
  if [ "$ok" = 1 ] && [ -n "$eh" ]; then PASS=$((PASS+1)); EXTRA=$((EXTRA+attempts)); echo "start[$i] OK attempts=$((attempts+1)) $eh" | tee -a "$OUT"
  else FAIL=$((FAIL+1)); echo "start[$i] FAIL" | tee -a "$OUT"; tail -3 /tmp/sup-out-$i.log >> "$OUT"; fi
  kill $RP 2>/dev/null; pkill -f "Devices/$UDID/.*ComposeHR.app/ComposeHR" 2>/dev/null
done
echo "=== supervised: ok=$PASS/$N fail=$FAIL retries_used=$EXTRA eh_ok=$EHOK/$N ===" | tee -a "$OUT"
