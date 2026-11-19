#!/usr/bin/env python3
"""Interleaved classifier on/off latency check (B4/B10).

Usage (from example-gradle, app running, daemons up): python3 accept-abab.py [pairs]

Each pair runs one reload with the classifier on and one with it off, the
order alternating per pair, so daemon warm-up and machine drift hit both sides
equally. "Off" = baseline snapshots moved to build/compat-off, which makes
`reload` skip the classifier. Prints per round: total ms (save -> new RENDER),
classifier ms, how long the send waited for the classifier (v8), and the
compile daemon's own request ms; then medians and the on-off difference.
"""
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.getcwd()
UI = os.path.join(HERE, "ui/src/main/kotlin/ui.kt")
APP = os.path.join(HERE, "app/src/main/kotlin/main.kt")
LOG = os.path.join(HERE, "launch.log")
DLOG = os.path.join(HERE, "hr-daemon.log")
COMPAT = os.path.join(HERE, "build/compat")
OFFDIR = os.path.join(HERE, "build/compat-off")
N = int(sys.argv[1]) if len(sys.argv) > 1 else 6
# Default: the reference driver (only it runs the classifier). HR_DRIVER=gradle
# goes through `./gradlew hotReload` instead.
DRIVER = os.environ.get("HR_DRIVER", "script")


# HR_DRIVER=script calls the reference driver directly (hotreload.sh ui-klib
# when ui sources changed, then hotreload.sh reload) instead of
# `./gradlew hotReload`. From P1 on, this example's Gradle tasks are the
# plugin's, so measuring the reference driver needs the script path.
class _Result:
    def __init__(self, rc, out, err):
        self.returncode, self.stdout, self.stderr = rc, out, err


def _ui_stale(here):
    klib = os.path.join(here, "build/ui/ui.klib")
    if not os.path.exists(klib):
        return True
    t = os.path.getmtime(klib)
    src = os.path.join(here, "ui/src/main/kotlin")
    return any(os.path.getmtime(os.path.join(d, f)) > t
               for d, _, fs in os.walk(src) for f in fs if f.endswith(".kt"))


def run_driver(here, env, driver):
    if driver != "script":
        return subprocess.run([os.path.join(here, "gradlew"), "hotReload", "--console=plain"],
                              cwd=here, env=env, capture_output=True, text=True)
    out, err = "", ""
    sh = os.path.join(here, "hotreload.sh")
    if _ui_stale(here):
        r = subprocess.run(["bash", sh, "ui-klib"], cwd=here, env=env, capture_output=True, text=True)
        out, err = r.stdout, r.stderr
        if r.returncode != 0:
            return _Result(r.returncode, out, err)
    r = subprocess.run(["bash", sh, "reload"], cwd=here, env=env, capture_output=True, text=True)
    return _Result(r.returncode, out + r.stdout, err + r.stderr)

CRE = re.compile(r"compat: timing ms=(\d+)")
WRE = re.compile(r"compat: wait ms=(\d+)")


def tail():
    with open(LOG, "rb") as fh:
        fh.seek(max(0, os.path.getsize(LOG) - 200000))
        return fh.read().replace(b"\r", b"\n").decode("utf-8", "replace")


def daemon_reqs():
    return [int(l.split()[-1]) for l in open(DLOG) if l.startswith("REQ")]


def set_mode(on):
    os.makedirs(OFFDIR, exist_ok=True)
    src, dst = (OFFDIR, COMPAT) if on else (COMPAT, OFFDIR)
    for f in ("ui.json", "app.json"):
        if os.path.exists(os.path.join(src, f)):
            shutil.move(os.path.join(src, f), os.path.join(dst, f))


def edit(path, pattern, value):
    s = open(path).read()
    s2 = re.sub(pattern, value, s)
    assert s2 != s
    open(path, "w").write(s2)


def one(kind, tag, on):
    set_mode(on)
    if kind == "ui":
        edit(UI, r'const val UI_MARK = "[^"]*"', f'const val UI_MARK = "{tag}"')
    else:
        edit(APP, r'private const val APP_MARK = "[^"]*"', f'private const val APP_MARK = "{tag}"')
    n0 = len(daemon_reqs())
    t0 = time.time()
    r = run_driver(HERE, dict(os.environ), DRIVER)
    found = False
    while time.time() - t0 < 60:
        if f"RENDER_UI {tag}" in tail() or f"RENDER_APP {tag}" in tail():
            found = True
            break
        time.sleep(0.05)
    dt = (time.time() - t0) * 1000
    m = CRE.search(r.stdout + r.stderr)
    w = WRE.search(r.stdout + r.stderr) if on else None
    reqs = daemon_reqs()[n0:]
    return found, dt, int(m.group(1)) if m else 0, reqs[-1] if reqs else None, int(w.group(1)) if w else 0


def med(v):
    v = sorted(v)
    return v[len(v) // 2] if len(v) % 2 else (v[len(v) // 2 - 1] + v[len(v) // 2]) / 2


res = {}
failed = 0
for kind in ("ui", "app"):
    for i in range(N):
        order = (True, False) if i % 2 == 0 else (False, True)
        for on in order:
            tag = f"{kind}x{i}{'n' if on else 'f'}"
            found, dt, cms, dms, wms = one(kind, tag, on)
            res.setdefault((kind, on), []).append((dt, cms, dms, wms))
            if not found:
                failed += 1
            print(f"{kind} {'ON ' if on else 'OFF'} {i} found={found} total={dt:.0f} compat={cms} wait={wms} "
                  f"daemon={dms}", flush=True)
set_mode(True)
print("=== ABAB summary (median) ===")
for kind in ("ui", "app"):
    on, off = res[(kind, True)], res[(kind, False)]
    t_on, t_off = med([x[0] for x in on]), med([x[0] for x in off])
    d_on, d_off = med([x[2] for x in on if x[2]]), med([x[2] for x in off if x[2]])
    print(f"{kind}: total on={t_on:.0f} off={t_off:.0f} delta={t_on - t_off:.0f} | classifier={med([x[1] for x in on]):.0f} "
          f"send-wait={med([x[3] for x in on]):.0f} | daemon compile on={d_on:.0f} off={d_off:.0f} delta={d_on - d_off:.0f}")
print(f"rounds without a new RENDER: {failed}")
sys.exit(1 if failed else 0)
