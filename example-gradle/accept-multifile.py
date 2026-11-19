#!/usr/bin/env python3
"""Three rounds of changing TWO files inside the ui module at once."""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
UI = os.path.join(HERE, "ui/src/main/kotlin/ui.kt")
UI2 = os.path.join(HERE, "ui/src/main/kotlin/ui2.kt")
LOG = os.path.join(HERE, sys.argv[1] if len(sys.argv) > 1 else "launch-gradle2.log")
env = dict(os.environ)


def edit(path, pattern, value):
    s = open(path).read()
    s2 = re.sub(pattern, value, s)
    assert s2 != s, f"edit failed {path}"
    open(path, "w").write(s2)


def tail():
    with open(LOG, "rb") as fh:
        fh.seek(max(0, os.path.getsize(LOG) - 300000))
        return fh.read().replace(b"\r", b"\n").decode("utf-8", "replace")


# Scope to one simulator when UDID is set, so another app instance on a second
# simulator (parallel verification) is never mistaken for ours.
PROC_PATTERN = (f"Devices/{os.environ['UDID']}/.*ComposeHR.app/ComposeHR"
                if os.environ.get("UDID") else "ComposeHR.app/ComposeHR")


def pid():
    out = subprocess.run(["pgrep", "-f", PROC_PATTERN],
                         capture_output=True, text=True).stdout.split()
    return out[0] if out else None


pid0 = pid()
print("pid0", pid0, flush=True)
for i in range(3):
    u, u2 = f"ui-mf{i}", f"ui2-mf{i}"
    edit(UI, r'const val UI_MARK = "[^"]*"', f'const val UI_MARK = "{u}"')
    edit(UI2, r'const val UI2_MARK = "[^"]*"', f'const val UI2_MARK = "{u2}"')
    t0 = time.time()
    r = subprocess.run([os.path.join(HERE, "gradlew"), "hotReload", "--console=plain"],
                       cwd=HERE, env=env, capture_output=True, text=True)
    found = False
    while time.time() - t0 < 90:
        d = tail()
        if f"RENDER_UI {u}" in d and f"RENDER_UI2 {u2}" in d:
            found = True
            break
        time.sleep(0.05)
    d = tail()
    same_tick = re.search(rf"RENDER_UI {u} tick=(\d+).*?RENDER_UI2 {u2} tick=\1", d, re.S) is not None
    changed = re.search(r"changed objects: app=(\d+) ui=(\d+)", r.stdout)
    syms = re.findall(r"symbols: ui defined=(\d+) kept_in_merged=(\d+)", r.stdout)
    ok = found and same_tick and changed and int(changed.group(2)) == 2 and syms and int(syms[0][0]) > 0
    print(f"mf[{i}] {'OK' if ok else 'FAIL'} {(time.time()-t0)*1000:.0f}ms "
          f"changed={changed.group(0) if changed else 'missing'} "
          f"symbols={syms} same_tick={same_tick} pid={pid()}", flush=True)
