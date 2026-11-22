#!/bin/bash
# Install and launch the built app. Retries if the process dies before its first frame
# (known intermittent ORC/JITLink segfault during bootstrap; see LIMITATIONS).
set -e
cd "$(dirname "$0")"
if [ -f "$(dirname "$0")/hotreload.env" ]; then
  set -a
  . "$(dirname "$0")/hotreload.env"
  set +a
fi
UDID=${UDID:-0B879832-98D9-49A5-AC00-8BAB579AFBB2}
APP=${1:-$PWD/build/ComposeHR.app}
BUNDLE=com.example.composehr
LOG=${LAUNCH_LOG:-$PWD/launch.log}
RETRIES=${LAUNCH_RETRIES:-3}
WAIT=${LAUNCH_WAIT:-60}
# Issue #108: libKaldo writes /tmp/perf-<pid>.map (about 70 MB per launch) whenever KALDO_PERF_MAP is set, whatever its
# value, so it is passed only with HR_PERF_MAP=1 (to symbolize JIT frames). This launch's file is deleted when the script
# exits unless HR_PERF_MAP_KEEP=1.
PERF_ENV=()
[ "${HR_PERF_MAP:-0}" = 1 ] && PERF_ENV=(SIMCTL_CHILD_KALDO_PERF_MAP=1)
APP_PID=""
perf_cleanup() {
  [ "${HR_PERF_MAP:-0}" = 1 ] && [ "${HR_PERF_MAP_KEEP:-0}" != 1 ] && [ -n "$APP_PID" ] && rm -f "/tmp/perf-$APP_PID.map"
  return 0
}
trap perf_cleanup EXIT
trap 'exit 143' TERM INT

if [ "${SKIP_INSTALL:-0}" != "1" ]; then
  xcrun simctl uninstall "$UDID" "$BUNDLE" 2>/dev/null || true
  xcrun simctl install "$UDID" "$APP"
fi

for attempt in $(seq 1 "$RETRIES"); do
  : > "$LOG"
  SIMCTL_CHILD_KONAN_ORC_RUNTIME_PATH="${ORC_RUNTIME:-$PWD/tools/liborc_rt_iossim_arm64.a}" \
  SIMCTL_CHILD_KALDO_SYNTH_EH_FRAME=${KALDO_SYNTH_EH_FRAME:-1} \
  SIMCTL_CHILD_KALDO_HOT_SYMBOLS=$PWD/build/app/hot-symbols.txt \
  SIMCTL_CHILD_KONAN_HOT_RELOAD_PORT=${KONAN_HOT_RELOAD_PORT:-5567} \
  SIMCTL_CHILD_KONAN_HR_FORCE_RECOMPOSE=${KONAN_HR_FORCE_RECOMPOSE:-1} \
  SIMCTL_CHILD_KONAN_HR_READY_MARKER=${KONAN_HR_READY_MARKER:-1} \
    env ${PERF_ENV[@]+"${PERF_ENV[@]}"} xcrun simctl launch --console-pty "$UDID" "$BUNDLE" ${APP_ARGS:-} 2>&1 | tee -a "$LOG" &
  LP=$!
  ok=0; alive=1
  deadline=$((SECONDS + WAIT))
  while [ "$SECONDS" -lt "$deadline" ]; do
    sleep 0.4
    if grep -q "RENDER_APP" "$LOG" 2>/dev/null; then ok=1; break; fi
    kill -0 "$LP" 2>/dev/null || { alive=0; break; }
  done
  # EH probe (main.kt): an exception thrown through JIT frames must be caught.
  # Its absence is a real regression, not the intermittent bootstrap crash, so
  # it is never retried.
  if grep -q "APP MAIN" "$LOG" 2>/dev/null && ! grep -q "^EH OK" "$LOG" 2>/dev/null; then
    sleep 1
    if ! grep -q "^EH OK" "$LOG" 2>/dev/null; then
      echo "EH probe failed: 'APP MAIN' without 'EH OK' (log $LOG)" >&2
      kill "$LP" 2>/dev/null || true
      exit 2
    fi
  fi
  APP_PID=$(pgrep -n -f "Devices/$UDID/.*/$(basename "$APP")/" || true)
  if [ "$ok" = 1 ]; then
    echo "started (attempt $attempt, $(grep -m1 '^EH OK' "$LOG" | tr -d '\r'), log $LOG)"
    wait "$LP"
    exit 0
  fi
  if [ "$alive" = 1 ]; then
    echo "still no first frame after ${WAIT}s; leaving the app running (log $LOG)"
    exit 0
  fi
  echo "attempt $attempt died before first frame; retrying" >&2
done
echo "failed to start after $RETRIES attempts (log $LOG)" >&2
exit 1
