#!/usr/bin/env python3
"""Acceptance for a three-module project (app -> ui -> core) driven by the plugin.

N rounds each of: change only core, only ui, only app; then 3 rounds changing all three at once.
Each round: edit, run `gradlew hotReload`, wait for the new RENDER tag(s), check the other modules'
tags did not change, every changed module passed the symbol check, all-three rounds land in the same
tick, and the app PID never changes.

Usage: accept-three.py [N]   (HR_UDID=<udid> restricts the PID lookup to one simulator)
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
GRADLEW = os.path.join(HERE, "../../../example-gradle/gradlew")
LOG = os.path.join(HERE, "launch.log")
N = int(sys.argv[1]) if len(sys.argv) > 1 else 5
FILES = {
    "core": (os.path.join(HERE, "core/src/main/kotlin/core.kt"), r'const val CORE_MARK = "[^"]*"', 'const val CORE_MARK = "{}"', "RENDER_CORE"),
    "ui": (os.path.join(HERE, "ui/src/main/kotlin/ui.kt"), r'const val UI_MARK = "[^"]*"', 'const val UI_MARK = "{}"', "RENDER_UI"),
    "app": (os.path.join(HERE, "app/src/main/kotlin/main.kt"), r'private const val APP_MARK = "[^"]*"', 'private const val APP_MARK = "{}"', "RENDER_APP"),
}


def pid():
    pat = (f"Devices/{os.environ['HR_UDID']}/.*" if os.environ.get("HR_UDID") else "") + "HR3.app/HR3"
    out = subprocess.run(["pgrep", "-f", pat], capture_output=True, text=True).stdout.split()
    return out[0] if out else None


def tail():
    with open(LOG, "rb") as fh:
        fh.seek(max(0, os.path.getsize(LOG) - 300000))
        return fh.read().replace(b"\r", b"\n").decode("utf-8", "replace")


def last_tag(marker):
    found = re.findall(rf"{marker} (\S+) tick=", tail())
    return found[-1] if found else None


def edit(module, tag):
    path, pattern, repl, _ = FILES[module]
    s = open(path).read()
    s2 = re.sub(pattern, repl.format(tag), s)
    assert s2 != s, f"edit failed: {path}"
    open(path, "w").write(s2)


def wait(markers_tags, deadline=90):
    t0 = time.time()
    while time.time() - t0 < deadline:
        d = tail()
        if all(f"{m} {t} tick=" in d for m, t in markers_tags):
            return True
        time.sleep(0.05)
    return False


pid0 = pid()
print("pid0", pid0, flush=True)
results = {}
for kind in ("core", "ui", "app"):
    ok = 0
    times = []
    for i in range(N):
        tag = f"{kind}-r{i}"
        others = {k: last_tag(FILES[k][3]) for k in FILES if k != kind}
        edit(kind, tag)
        t0 = time.time()
        r = subprocess.run([GRADLEW, "-p", HERE, "hotReload", "--console=plain"], capture_output=True, text=True)
        found = wait([(FILES[kind][3], tag)])
        dt = (time.time() - t0) * 1000
        sym = re.search(rf"symbols: {kind} defined=(\d+) kept_in_merged=(\d+)", r.stdout)
        sym_ok = bool(sym) and int(sym.group(1)) > 0 and sym.group(1) == sym.group(2)
        others_ok = all(last_tag(FILES[k][3]) == v for k, v in others.items())
        good = r.returncode == 0 and found and sym_ok and others_ok and pid() == pid0
        ok += good
        if good:
            times.append(dt)
        print(f"{kind}[{i}] {'OK' if good else 'FAIL'} {dt:.0f}ms {sym.group(0) if sym else 'symbols missing'} "
              f"others_unchanged={others_ok} pid={pid()}", flush=True)
        if not good and r.returncode != 0:
            print(r.stdout[-2000:] + r.stderr[-2000:], flush=True)
    results[kind] = (ok, times)

ok = 0
times = []
for i in range(3):
    tags = {k: f"{k}-all{i}" for k in FILES}
    for k, t in tags.items():
        edit(k, t)
    t0 = time.time()
    r = subprocess.run([GRADLEW, "-p", HERE, "hotReload", "--console=plain"], capture_output=True, text=True)
    found = wait([(FILES[k][3], t) for k, t in tags.items()])
    dt = (time.time() - t0) * 1000
    d = tail()
    ticks = {k: re.findall(rf"{FILES[k][3]} {t} tick=(\d+)", d) for k, t in tags.items()}
    same_tick = all(ticks.values()) and len({v[0] for v in ticks.values()}) == 1
    syms = dict((m, int(n)) for m, n in re.findall(r"symbols: (\w+) defined=(\d+)", r.stdout))
    sym_ok = all(syms.get(k, 0) > 0 for k in FILES)
    good = r.returncode == 0 and found and same_tick and sym_ok and pid() == pid0
    ok += good
    if good:
        times.append(dt)
    print(f"all[{i}] {'OK' if good else 'FAIL'} {dt:.0f}ms first_ticks={ {k: v[:1] for k, v in ticks.items()} } "
          f"same_tick={same_tick} symbols={syms} pid={pid()}", flush=True)
results["all"] = (ok, times)

print("=== summary ===")
for k, (ok, times) in results.items():
    n = 3 if k == "all" else N
    p50 = sorted(times)[len(times) // 2] if times else 0
    print(f"{k}: ok={ok}/{n} p50={p50:.0f}ms")
