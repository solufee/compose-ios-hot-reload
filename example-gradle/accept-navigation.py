#!/usr/bin/env python3
"""Real taps (AXe): `+` x3, then Next/Back three times. Checks the counter survives page changes,
the page actually changes, the PID never changes, and no exception escaped (composable dispose on
Back is what needs exceptions to unwind through JIT frames, INTERFACE §3.3 #1/#2).

Usage: HR_UDID=<udid> accept-navigation.py
"""
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "launch.log")
UDID = os.environ["HR_UDID"]
BAD = ("HOOK ", "Uncaught", "ModifierNodeDetachedCancellationException", "terminating due to")


def pid():
    out = subprocess.run(["pgrep", "-f", f"Devices/{UDID}/.*ComposeHR.app/ComposeHR"], capture_output=True, text=True).stdout.split()
    return out[0] if out else None


def log():
    return open(LOG, "rb").read().replace(b"\r", b"\n").decode("utf-8", "replace")


def last_state():
    m = re.findall(r"RENDER_APP \S+ tick=\d+ count=(\d+) page=(\d+)", log())
    return (int(m[-1][0]), int(m[-1][1])) if m else (None, None)


# Compose buttons expose no accessibility labels to AXe, so tap by position (points, iPhone 18 Pro portrait;
# read off a screenshot of this example).
POINTS = {"+": (247, 499), "Next": (201, 569), "Back": (201, 528)}


def tap(label):
    x, y = POINTS[label]
    r = subprocess.run(["axe", "tap", "-x", str(x), "-y", str(y), "--udid", UDID], capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f"tap {label!r} failed: {r.stderr.strip()}")


def wait_for(count, page, deadline=10):
    t0 = time.time()
    while time.time() - t0 < deadline:
        if last_state() == (count, page):
            return True
        time.sleep(0.1)
    return False


pid0 = pid()
start = len(log())
print("pid0", pid0, "state", last_state(), flush=True)
count0, _ = last_state()
ok = True
for i in range(3):
    tap("+")
    good = wait_for(count0 + i + 1, 0)
    ok &= good
    print(f"plus[{i}] {'OK' if good else 'FAIL'} state={last_state()}", flush=True)
count = count0 + 3
for i in range(3):
    tap("Next")
    a = wait_for(count, 1)
    tap("Back")
    b = wait_for(count, 0)
    good = a and b and pid() == pid0
    ok &= good
    print(f"nav[{i}] {'OK' if good else 'FAIL'} next={a} back={b} state={last_state()} pid={pid()}", flush=True)
bad = [line for line in log()[start:].splitlines() if any(b in line for b in BAD)]
ok &= not bad and pid() == pid0
print(f"=== navigation: {'OK' if ok else 'FAIL'} counter={last_state()[0]} pid={pid()} (start {pid0}) bad_lines={len(bad)} ===")
for line in bad[:5]:
    print("  ", line)
