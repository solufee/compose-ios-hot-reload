#!/usr/bin/env python3
"""Cold-start check through the plugin: N times `gradlew hotReloadRun` (terminate + launch, no reinstall).

A start counts only if the app is up on the first attempt (the plugin retries up to 3 times; any
retry is a failure here). Usage: accept-coldstart.py [N] [extra gradle args...]
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
N = int(sys.argv[1]) if len(sys.argv) > 1 else 10
extra = sys.argv[2:]
ok = 0
pids = []
for i in range(N):
    t0 = time.time()
    r = subprocess.run([os.path.join(HERE, "gradlew"), "-p", HERE, "hotReloadRun", "--console=plain",
                        "-Pcmp.hotreload.reinstall=false"] + extra, capture_output=True, text=True)
    m = re.search(r"app started \(attempt (\d+), pid (\d+), ([0-9.]+) s", r.stdout)
    # EH (INTERFACE §3.3 #1/#2): the startup log must show synthesized FDEs, no anomalies, whole-section registration.
    log = open(os.path.join(HERE, "launch.log"), "rb").read().replace(b"\r", b"\n").decode("utf-8", "replace")
    eh = [tuple(map(int, x)) for x in re.findall(r"EH check: (\d+) FDE, (\d+) lsda, (\d+) lsda-out-of-range, (\d+) pc-out-of-range", log)]
    fde, lsda, bad1, bad2 = max(eh) if eh else (0, 0, 0, 0)
    reg = re.findall(r"registered (\d+) \(section\)", log)
    probe = "EH OK eh-probe" in log  # INTERFACE v4: the reference app's EH probe on every start
    eh_ok = fde > 0 and bad1 == 0 and bad2 == 0 and str(fde) in reg and probe
    first = bool(m) and m.group(1) == "1" and eh_ok
    ok += first
    if m:
        pids.append(m.group(2))
    print(f"start[{i}] {'OK' if first else 'FAIL'} attempt={m.group(1) if m else '-'} pid={m.group(2) if m else '-'} "
          f"app={m.group(3) if m else '-'}s total={time.time() - t0:.1f}s EH={fde} FDE/{lsda} lsda/{bad1}+{bad2} bad/registered={reg[-1] if reg else '-'} probe={probe}", flush=True)
    if not m:
        print(r.stdout[-1500:] + r.stderr[-1500:], flush=True)
print(f"=== cold start: ok={ok}/{N} distinct_pids={len(set(pids))} ===")
