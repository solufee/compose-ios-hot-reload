#!/usr/bin/env python3
"""Acceptance for the Gradle wiring: 20 ui-only + 20 app-only + 3 both-module reloads.

HR_DRIVER=script measures the reference driver (hotreload.sh) instead of
`./gradlew hotReload` (which is the plugin's task from P1 on).

For every round: edit the source, run `./gradlew hotReload`, wait for the new
RENDER line(s), check that the *other* module's tag did not change, and that the
symbol check printed a non-zero count for every changed module.
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
UI = os.path.join(HERE, "ui/src/main/kotlin/ui.kt")
APP = os.path.join(HERE, "app/src/main/kotlin/main.kt")
LOG = os.path.join(HERE, sys.argv[1] if len(sys.argv) > 1 else "launch-gradle1.log")
N = int(sys.argv[2]) if len(sys.argv) > 2 else 20

env = dict(os.environ)
env.setdefault("JAVA_HOME", os.path.expanduser(
    "~/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.21+9/Contents/Home"))


# Scope to one simulator when UDID is set, so another app instance on a second
# simulator (parallel verification) is never mistaken for ours.
PROC_PATTERN = (f"Devices/{os.environ['UDID']}/.*ComposeHR.app/ComposeHR"
                if os.environ.get("UDID") else "ComposeHR.app/ComposeHR")


def pid():
    out = subprocess.run(["pgrep", "-f", PROC_PATTERN],
                         capture_output=True, text=True).stdout.split()
    return out[0] if out else None


def edit(path, pattern, value):
    s = open(path).read()
    s2 = re.sub(pattern, value, s)
    assert s2 != s, f"edit failed: {path} {pattern}"
    open(path, "w").write(s2)


def tail():
    with open(LOG, "rb") as fh:
        fh.seek(max(0, os.path.getsize(LOG) - 300000))
        return fh.read().replace(b"\r", b"\n").decode("utf-8", "replace")


def wait_render(tag, deadline=90):
    t0 = time.time()
    while time.time() - t0 < deadline:
        d = tail()
        if f"RENDER_UI {tag}" in d or f"RENDER_APP {tag}" in d:
            return True
        time.sleep(0.05)
    return False


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


DRIVER = os.environ.get("HR_DRIVER", "gradle")


def run_gradle():
    return run_driver(HERE, env, DRIVER)


def pct(v, q):
    v = sorted(v)
    k = (len(v) - 1) * q
    lo, hi = int(k), min(int(k) + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


pid0 = pid()
print("pid0", pid0, flush=True)
res = {"ui": [], "app": [], "both": []}
# Classifier step (interface v7): `compat: timing ms=... ui_snapshot=... app_snapshot=... classify=...`
COMPAT_RE = re.compile(r"compat: timing ms=(\d+) ui_snapshot=(\d+) app_snapshot=(\d+) classify=(\d+)")
compat = {"ui": [], "app": [], "both": []}
# v8: the classifier runs beside the compile; `compat: wait ms=N` is how long
# the send actually waited for its verdict (the part that adds latency).
WAIT_RE = re.compile(r"compat: wait ms=(\d+)")
waits = {"ui": [], "app": [], "both": []}


def compat_of(kind, r):
    m = COMPAT_RE.search(r.stdout + r.stderr)
    w = WAIT_RE.search(r.stdout + r.stderr)
    wait = ""
    if w:
        waits[kind].append(int(w.group(1)))
        wait = f" wait={w.group(1)}ms"
    if not m:
        return "compat=none" + wait
    compat[kind].append(tuple(int(x) for x in m.groups()))
    return f"compat={m.group(1)}ms(ui={m.group(2)} app={m.group(3)} cls={m.group(4)}){wait}"
ok = {"ui": 0, "app": 0, "both": 0}
ui_tag, app_tag = None, None
for kind in ("ui", "app"):
    for i in range(N):
        tag = f"{kind}{i}"
        if kind == "ui":
            edit(UI, r'const val UI_MARK = "[^"]*"', f'const val UI_MARK = "{tag}"')
            before_app = tail().split("RENDER_APP ")[-1].split()[0]
        else:
            edit(APP, r'private const val APP_MARK = "[^"]*"', f'private const val APP_MARK = "{tag}"')
            before_ui = tail().split("RENDER_UI ")[-1].split()[0]
        t0 = time.time()
        r = run_gradle()
        found = wait_render(tag)
        dt = (time.time() - t0) * 1000
        checked = re.search(rf"symbols: {kind} defined=(\d+) kept_in_merged=(\d+)", r.stdout)
        sym_ok = bool(checked) and int(checked.group(1)) > 0 and int(checked.group(2)) > 0
        other_ok = (f"RENDER_APP {before_app}" in tail()) if kind == "ui" else (f"RENDER_UI {before_ui}" in tail())
        good = found and sym_ok and other_ok and pid() == pid0
        if good:
            ok[kind] += 1
            res[kind].append(dt)
        print(f"{kind}[{i}] {'OK' if good else 'FAIL'} total={dt:.0f}ms sym={checked.group(0) if checked else 'missing'} "
              f"found={found} other={other_ok} pid={pid()} {compat_of(kind, r)}", flush=True)

# both modules, fresh tags, must appear in the same tick
for i in range(3):
    ut, at = f"ub{i}", f"ab{i}"
    edit(UI, r'const val UI_MARK = "[^"]*"', f'const val UI_MARK = "{ut}"')
    edit(APP, r'private const val APP_MARK = "[^"]*"', f'private const val APP_MARK = "{at}"')
    t0 = time.time()
    r = run_gradle()
    found = wait_render(ut)
    dt = (time.time() - t0) * 1000
    d = tail()
    same_tick = re.search(rf"RENDER_APP {at} tick=(\d+).*?RENDER_UI {ut} tick=\1", d, re.S) is not None
    syms = re.findall(r"symbols: (app|ui) defined=(\d+)", r.stdout)
    sym_ok = len(syms) == 2 and all(int(n) > 0 for _, n in syms)
    good = found and same_tick and sym_ok and pid() == pid0
    if good:
        ok["both"] += 1
        res["both"].append(dt)
    print(f"both[{i}] {'OK' if good else 'FAIL'} total={dt:.0f}ms same_tick={same_tick} syms={syms} pid={pid()} "
          f"{compat_of('both', r)}", flush=True)

print("=== summary ===")
for k in ("ui", "app", "both"):
    v = res[k]
    print(f"{k}: ok={ok[k]}/{(N if k != 'both' else 3)} "
          f"p50={pct(v,.5):.0f}ms p90={pct(v,.9):.0f}ms" if v else f"{k}: ok=0")
for k in ("ui", "app", "both"):
    c = compat[k]
    if c:
        cols = list(zip(*c))
        print(f"{k} classifier: n={len(c)} total p50={pct(cols[0],.5):.0f}ms p90={pct(cols[0],.9):.0f}ms "
              f"(ui_snapshot p50={pct(cols[1],.5):.0f} app_snapshot p50={pct(cols[2],.5):.0f} "
              f"classify p50={pct(cols[3],.5):.0f})")
    else:
        print(f"{k} classifier: not run")
    if waits[k]:
        print(f"{k} send waited for classifier: p50={pct(waits[k],.5):.0f}ms p90={pct(waits[k],.9):.0f}ms")
