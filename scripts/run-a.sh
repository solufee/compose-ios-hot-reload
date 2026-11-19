#!/bin/bash
set -e
UDID=${UDID:-0B879832-98D9-49A5-AC00-8BAB579AFBB2}
cp ComposeHR.kexe ComposeHR.app/ComposeHR
cp Info.plist ComposeHR.app/Info.plist
mkdir -p ComposeHR.app/Frameworks
KALDO_DYLIB=${KALDO_DYLIB:?KALDO_DYLIB is required (<KALDO_INSTALL>/lib/libKaldo.iossim.dylib)}
cp "$KALDO_DYLIB" ComposeHR.app/Frameworks/
xcrun simctl uninstall "$UDID" com.example.composehr 2>/dev/null || true
xcrun simctl install "$UDID" ComposeHR.app
SIMCTL_CHILD_KONAN_ORC_RUNTIME_PATH="$PWD/liborc_rt_iossim_arm64.a" SIMCTL_CHILD_KALDO_PERF_MAP=1 SIMCTL_CHILD_KALDO_SYNTH_EH_FRAME=1 \
  xcrun simctl launch --console-pty --terminate-running-process "$UDID" com.example.composehr
