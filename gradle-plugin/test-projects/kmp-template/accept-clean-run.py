#!/usr/bin/env python3
"""Plugin acceptance for #93: `hotReloadRun` right after `hotReloadClean` packages first, then launches.

  1  hotReloadClean, then hotReloadRun alone: "packaging it first", a package is built, the app starts
  2  hotReloadRun again: the existing package is launched as it is (no packaging), the app starts
  3  hotReloadClean + hotReloadRun in one command: packages first again, the app starts

Usage: accept-clean-run.py --zip WIZARD.zip --work DIR --plugin-repo URL --plugin-version V --toolchain DIR --udid U [--port 5568]
Stops what it started (the app by bundle id, the project's compile daemons) at the end.
"""
import argparse
import os
import shutil
import subprocess
import sys
import time
import zipfile

ap = argparse.ArgumentParser()
ap.add_argument("--zip", required=True)
ap.add_argument("--work", required=True)
ap.add_argument("--plugin-repo", required=True)
ap.add_argument("--plugin-version", required=True)
ap.add_argument("--toolchain", required=True)
ap.add_argument("--udid", required=True)
ap.add_argument("--port", default="5568")
a = ap.parse_args()

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "../../.."))
WORK = os.path.abspath(a.work)
P = os.path.join(WORK, "KmpHr")
HOME = os.path.expanduser("~")
EXE, BUNDLE = "KmpHr", "org.example.kmphr.hotreload"
results = []


def say(line=""):
    print(line.replace(WORK, "<WORK>").replace(REPO, "<REPO>").replace(HOME, "<HOME>"), flush=True)


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    say(f"{'PASS' if ok else 'FAIL'} {name}" + (f" | {detail}" if detail else ""))


def gradle(*args, timeout=1800):
    t0 = time.time()
    r = subprocess.run([os.path.join(P, "gradlew"), "-p", P, *args, "--console=plain"], cwd=P, capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout + r.stderr, time.time() - t0


def pid():
    o = subprocess.run(["pgrep", "-f", f"Devices/{a.udid}/.*/{EXE}.app/{EXE}"], capture_output=True, text=True).stdout.split()
    return o[0] if o else None


def edit(path, old, new):
    s = open(path).read()
    if old not in s:
        raise KeyError(f"{old!r} not in {path}")
    open(path, "w").write(s.replace(old, new, 1))


def ran(out, task):
    return any(l.startswith(f"> Task :shared:{task}") and "UP-TO-DATE" not in l and "SKIPPED" not in l for l in out.splitlines())


say(f"# #93 acceptance (hotReloadRun after hotReloadClean), plugin {a.plugin_version}, {time.strftime('%F %T')}, simulator {a.udid}")
if os.path.exists(WORK):
    shutil.rmtree(WORK)
os.makedirs(WORK)
zipfile.ZipFile(a.zip).extractall(WORK)
os.chmod(os.path.join(P, "gradlew"), 0o755)
edit(os.path.join(P, "settings.gradle.kts"), "pluginManagement {\n    repositories {\n",
     f'pluginManagement {{\n    repositories {{\n        maven {{ url = uri("{a.plugin_repo}") }}\n')
edit(os.path.join(P, "shared/build.gradle.kts"), "plugins {\n", f'plugins {{\n    id("com.solufee.cmp.hotreload") version "{a.plugin_version}"\n')
open(os.path.join(P, "local.properties"), "w").write(
    f"cmp.hotreload.toolchainDir={a.toolchain}\ncmp.hotreload.simulatorUdid={a.udid}\ncmp.hotreload.port={a.port}\n")
try:
    say("## 1. hotReloadClean, then hotReloadRun alone")
    rc, out, dt = gradle("hotReloadClean")
    check("hotReloadClean", rc == 0, f"{dt:.0f}s")
    rc, out, dt = gradle("hotReloadRun")
    first = "packaging it first" in out
    check("hotReloadRun with no package: packages first, then the app starts",
          rc == 0 and first and "package timing" in out and "app started" in out and pid() is not None, f"{dt:.0f}s pid {pid()}")
    if rc:
        say("\n".join(out.splitlines()[-30:]))
    say("## 2. hotReloadRun again")
    rc, out, dt = gradle("hotReloadRun")
    check("hotReloadRun with a package: launched as it is (no packaging), the app starts",
          rc == 0 and "packaging it first" not in out and "package timing" not in out and "app started" in out and pid() is not None,
          f"{dt:.0f}s pid {pid()}")
    say("## 3. hotReloadClean hotReloadRun in one command")
    rc, out, dt = gradle("hotReloadClean", "hotReloadRun")
    check("hotReloadClean hotReloadRun: packages first again, the app starts",
          rc == 0 and "packaging it first" in out and "app started" in out and pid() is not None, f"{dt:.0f}s pid {pid()}")
except Exception as e:
    check(f"script error: {type(e).__name__}: {e}", False)
finally:
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
    gradle("hotReloadDaemonStop")
    say(f"stopped {BUNDLE} and the compile daemons; app running: {pid() is not None}")
failed = [n for n, ok in results if not ok]
say(f"summary: {len(results) - len(failed)}/{len(results)} PASS" + ("" if not failed else f"; FAILED: {failed}"))
sys.exit(1 if failed else 0)
