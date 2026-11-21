#!/bin/bash
# #60 (b) A/B: APP MAIN -> APP DID FINISH with the simulator's ApplicationAccessibilityEnabled = 1 vs 0.
# Runs on a throwaway device it creates and deletes; the shared simulator's settings are not touched.
# Usage: TOOLCHAIN_ENV=<toolchain>/env.sh ax-ab.sh <out-dir> [device-type] [runtime]   (needs a packaged example: build/ComposeHR.app)
set -u
O=$1; DT=${2:-com.apple.CoreSimulator.SimDeviceType.iPhone-18-Pro}; RT=${3:-com.apple.CoreSimulator.SimRuntime.iOS-27-2}
HERE=$(cd "$(dirname "$0")" && pwd); EX=$(cd "$HERE/../.." && pwd); mkdir -p "$O"
. "$TOOLCHAIN_ENV"; cd "$EX"
D=$(xcrun simctl create hr60-ax "$DT" "$RT") || exit 1
echo "device $D" | tee "$O/device.txt"
xcrun simctl boot "$D"; xcrun simctl bootstatus "$D" -b > /dev/null
echo "fresh default: $(xcrun simctl spawn "$D" defaults read com.apple.Accessibility ApplicationAccessibilityEnabled 2>&1)" | tee -a "$O/device.txt"
export UDID=$D KONAN_HOT_RELOAD_PORT=${KONAN_HOT_RELOAD_PORT:-5590} LAUNCH_LOG=$O/launch.log
xcrun simctl install "$D" build/ComposeHR.app
for ax in 1 0 1 0; do   # second round of each value overwrites the first (checks that switching back works)
  xcrun simctl spawn "$D" defaults write com.apple.Accessibility ApplicationAccessibilityEnabled -bool "$([ $ax = 1 ] && echo YES || echo NO)"
  sleep 2
  for i in 1 2 3; do
    xcrun simctl terminate "$D" com.example.composehr 2>/dev/null; sleep 1
    rm -f "$O/stop"; : > "$O/launch.log"
    python3 "$HERE/watch-log.py" "$O/launch.log" "$O/ax$ax-$i.tl" "$O/stop" & WP=$!
    SKIP_INSTALL=1 bash run.sh > "$O/run.out" 2>&1 & RP=$!
    for _ in $(seq 1 300); do grep -q RENDER_APP "$O/launch.log" 2>/dev/null && break; sleep 0.1; done
    sleep 1; touch "$O/stop"; wait $WP
    xcrun simctl terminate "$D" com.example.composehr 2>/dev/null; kill $RP 2>/dev/null; wait $RP 2>/dev/null
  done
done
xcrun simctl shutdown "$D"; xcrun simctl delete "$D"; echo "deleted $D" | tee -a "$O/device.txt"
python3 "$HERE/axparse.py" "$O" | tee "$O/result.txt"
