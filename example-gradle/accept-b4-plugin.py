#!/usr/bin/env python3
"""B4 regression driven by the Gradle plugin (P2a): the same 16 change cases and 12-tap-after-warm as
accept-b4.py (the low-level line's reference, accept-b4-tap.txt), with the same checks and screenshots.

Only two steps differ from accept-b4.py:
  start  - `./gradlew hotReloadRun --args=--hr-state=2,3` (no run.sh, no pkill by name);
  reload - `./gradlew hotReload` (the plugin classifies, then hot-sends or warm-restarts).

Usage: UDID=<udid> python3 accept-b4-plugin.py    (after ./gradlew hotReloadPackage)
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import importlib.util  # noqa: E402

spec = importlib.util.spec_from_file_location("b4", os.path.join(HERE, "accept-b4.py"))
b4 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b4)

GRADLEW = os.path.join(HERE, "gradlew")


def gradle(*args, timeout=900):
    return subprocess.run([GRADLEW, "-p", HERE, *args, "--console=plain"], cwd=HERE, env=b4.ENV,
                          capture_output=True, text=True, timeout=timeout)


def start_fresh():
    subprocess.run(["xcrun", "simctl", "terminate", b4.UDID, b4.BUNDLE], capture_output=True)
    time.sleep(1)
    r = gradle("hotReloadRun", "--args=--hr-state=2,3")
    if r.returncode != 0:
        raise RuntimeError("hotReloadRun failed:\n" + (r.stdout + r.stderr)[-1500:])
    if not b4.wait_log(b4.LOG, r"count=3 page=2", since=0):
        raise RuntimeError("app did not start at page=2 count=3")
    time.sleep(2.5)  # let the first frame composite before screenshotting
    return b4.pid()


def reload_once(warm_expected):
    """`gradlew hotReload` (compiles the changed klibs itself). Returns (ok, out, marks, pid_after)."""
    before = len(b4.read(b4.LOG))
    t0 = time.time()
    r = gradle("hotReload")
    out = r.stdout + r.stderr
    if r.returncode != 0:
        return False, out, None, b4.pid()
    # A warm restart truncates the launch log when it relaunches; check_* handle a shrunk log.
    return True, out, (before, before, t0), b4.pid()


_check_warm = b4.check_warm


def check_warm(case, expected_shot, out, marks, p_before):
    """Same check as accept-b4.py, plus the plugin's WARM OK timing line in the detail."""
    good, detail, s, p_after = _check_warm(case, expected_shot, out, marks, p_before)
    m = re.search(r"WARM OK ms=(\d+) ms_package=(\d+) ms_relaunch=(\d+)", out)
    if m:
        detail += f" warm_ms={m.group(1)} package_ms={m.group(2)} relaunch_ms={m.group(3)}"
    p = re.search(r"package timing ms=(\d+) compile=(\d+) merge=(\d+) embed\+bundle=(\d+)", out)
    if p:
        detail += f" [compile={p.group(2)} merge={p.group(3)} embed+bundle={p.group(4)}]"
    return good, detail, s, p_after


b4.start_fresh = start_fresh
b4.reload_once = reload_once
b4.check_warm = check_warm

if __name__ == "__main__":
    sys.exit(b4.main())
