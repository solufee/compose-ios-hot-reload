#!/usr/bin/env python3
"""#107: libKaldo's perf map (/tmp/perf-<pid>.map, ~70 MB per app start) is written only with cmp.hotreload.perfMap=true,
and the plugin deletes the maps of the app processes it started once they are gone. /tmp is only listed, never cleaned here.
  A  default (no switch): package -> run -> 3 HOT edits -> 1 WARM (top-level property added): no perf map for any of this
     test's app pids (maps of other processes started meanwhile are listed, not counted)
  B  -Pcmp.hotreload.perfMap=true: run -> the app's map exists; WARM -> the old process's map is gone, the new one exists;
     hotReloadRun -> the same; app stopped + hotReloadClean -> its map is gone; in the end none of this test's app pids has a map
Usage: accept-perf-map.py --zip WIZARD.zip --work DIR --plugin-repo URL --plugin-version V --toolchain DIST --udid U [--port N]
"""
import argparse
import os
import re
import shutil
import subprocess
import time
import zipfile

ap = argparse.ArgumentParser()
for k in ("zip", "work", "plugin-repo", "plugin-version", "toolchain", "udid"):
    ap.add_argument("--" + k, required=True)
ap.add_argument("--port", default="5568")
a = ap.parse_args()
WORK = os.path.abspath(a.work)
HOME = os.path.expanduser("~")
BUNDLE, EXE = "org.example.kmphr.hotreload", "KmpHr"
results = []


def say(t=""):
    print(t.replace(WORK, "<WORK>").replace(HOME, "<HOME>"), flush=True)


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    say(f"{'PASS' if ok else 'FAIL'} {name}" + (f" | {detail}" if detail else ""))


def maps():
    return {n for n in os.listdir("/tmp") if n.startswith("perf-") and n.endswith(".map")}


def mapfile(pid):
    return f"/tmp/perf-{pid}.map"


def gradle(*args):
    t0 = time.time()
    r = subprocess.run([os.path.join(P, "gradlew"), "-p", P, *args, "--console=plain"], cwd=P, capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr, time.time() - t0


def pid():
    o = subprocess.run(["pgrep", "-f", f"Devices/{a.udid}/.*/{EXE}.app/{EXE}"], capture_output=True, text=True).stdout.split()
    return o[0] if o else None


APP = None
EDIT = [0]


def edit_hot():
    EDIT[0] += 1
    s = open(APP).read()
    s2 = re.sub(r'Text\("Click me![^"]*"\)', f'Text("Click me! pm{EDIT[0]}")', s, count=1)
    assert s2 != s
    open(APP, "w").write(s2)


def edit_warm(n):
    open(APP, "a").write(f"\nval perfMapWarmProbe{n} = {n}\n")


shutil.rmtree(WORK, ignore_errors=True)
os.makedirs(WORK)
with zipfile.ZipFile(a.zip) as z:
    z.extractall(WORK)
P = os.path.join(WORK, "KmpHr")
os.chmod(os.path.join(P, "gradlew"), 0o755)
s = open(os.path.join(P, "settings.gradle.kts")).read()
open(os.path.join(P, "settings.gradle.kts"), "w").write(
    s.replace("pluginManagement {\n    repositories {\n", f'pluginManagement {{\n    repositories {{\n        maven {{ url = uri("{a.plugin_repo}") }}\n', 1))
b = open(os.path.join(P, "shared/build.gradle.kts")).read()
open(os.path.join(P, "shared/build.gradle.kts"), "w").write(
    b.replace("    alias(libs.plugins.composeCompiler)\n}", f'    alias(libs.plugins.composeCompiler)\n    id("com.solufee.cmp.hotreload") version "{a.plugin_version}"\n}}', 1))
open(os.path.join(P, "local.properties"), "w").write(
    f"cmp.hotreload.toolchainDir={a.toolchain}\ncmp.hotreload.simulatorUdid={a.udid}\ncmp.hotreload.port={a.port}\n")
APP = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/App.kt")
say(f"# #107 acceptance (perf map), {time.strftime('%F %T')}, plugin {a.plugin_version}, simulator {a.udid}")
before = maps()
say(f"perf maps in /tmp before: {len(before)}")
try:
    say("## A default: package, run, 3 HOT, 1 WARM")
    rc, out, dt = gradle("hotReloadPackage")
    check(f"A: package ({dt:.0f}s)", rc == 0)
    rc, out, dt = gradle("hotReloadRun")
    p0 = pid()
    check(f"A: run ({dt:.0f}s)", rc == 0 and p0 is not None, f"pid {p0}")
    for i in range(3):
        edit_hot()
        rc, out, dt = gradle("hotReload")
        check(f"A: HOT {i + 1} ({dt:.1f}s)", rc == 0 and "compat: HOT" in out and pid() == p0)
    edit_warm(1)
    rc, out, dt = gradle("hotReload")
    p1 = pid()
    check(f"A: WARM ({dt:.1f}s)", rc == 0 and ("WARM OK" in out or "compat: WARM" in out) and p1 not in (None, p0), f"pid {p0}->{p1}")
    ours = [mapfile(x) for x in (p0, p1) if x]
    check("A: no perf map for this test's app processes (the switch is off by default)", not any(os.path.exists(m) for m in ours),
          f"pids {p0}, {p1}")
    other = sorted(maps() - before - {os.path.basename(m) for m in ours})
    if other:
        say(f"  (new perf maps of other processes, e.g. apps started at the same time by plugins without #107: {other})")

    say("## B -Pcmp.hotreload.perfMap=true")
    ON = "-Pcmp.hotreload.perfMap=true"
    rc, out, dt = gradle("hotReloadRun", ON)
    q0 = pid()
    time.sleep(2)
    check(f"B: run ({dt:.0f}s): the app's perf map exists", rc == 0 and q0 is not None and os.path.isfile(mapfile(q0)),
          f"pid {q0}, {os.path.getsize(mapfile(q0)) // (1024 * 1024) if q0 and os.path.isfile(mapfile(q0)) else 0} MB")
    edit_warm(2)
    rc, out, dt = gradle("hotReload", ON)
    q1 = pid()
    time.sleep(2)
    check(f"B: WARM ({dt:.1f}s): the old process's map is deleted, the new one exists",
          rc == 0 and q1 not in (None, q0) and not os.path.exists(mapfile(q0)) and os.path.isfile(mapfile(q1)),
          f"pid {q0}->{q1}; " + "; ".join(l.strip() for l in out.splitlines() if "perf map" in l)[:200])
    rc, out, dt = gradle("hotReloadRun", ON)
    q2 = pid()
    time.sleep(2)
    check(f"B: hotReloadRun again ({dt:.0f}s): the previous map is deleted",
          rc == 0 and q2 not in (None, q1) and not os.path.exists(mapfile(q1)) and os.path.isfile(mapfile(q2)), f"pid {q1}->{q2}")
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
    time.sleep(1)
    rc, out, dt = gradle("hotReloadClean")
    for l in out.splitlines():
        if "perf map" in l:
            say("  | " + l.strip())
    check("B: app stopped + hotReloadClean: its map is deleted", rc == 0 and not os.path.exists(mapfile(q2)))
    ours = [mapfile(x) for x in (q0, q1, q2) if x]
    check("B: in the end none of this test's app processes left a perf map", not any(os.path.exists(m) for m in ours), f"pids {q0}, {q1}, {q2}")
    other = sorted(maps() - before - {os.path.basename(m) for m in ours})
    if other:
        say(f"  (new perf maps of other processes, not touched: {other})")
except Exception as e:
    check(f"script error: {type(e).__name__}: {e}", False)
finally:
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
    gradle("hotReloadDaemonStop")
failed = [n for n, ok in results if not ok]
say(f"summary: {len(results) - len(failed)}/{len(results)} PASS" + ("" if not failed else f"; FAILED: {failed}"))
