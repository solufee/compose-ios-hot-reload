#!/usr/bin/env python3
"""Plugin acceptance for #80: an exception thrown in patched code and caught in patched code.

The wizard template gets commonMain/Exc.kt: `thrower(kind, n)` throws a ClassCastException (a failed cast), a
NullPointerException (`!!` on null) or a custom exception; `catcher()` calls it for each kind and catches each one.
App() prints `EXC <tag> <results>` on every composition into the launch log.

  1  package, run: `EXC v1 cce,npe,app:custom1-1`
  2  three rounds: the bodies of thrower and catcher change (tag v1 -> v2 -> v3 -> v4, the custom message too), each
     `hotReload` HOT in the same process, and the app prints the new line with all three exceptions caught
  3  no `Uncaught` / crash in the launch log; one app process throughout

Without unwind info for the patch's frames (#80, before plugin 0.2.8-rc3) the first throw in patched code ends the app.

Usage: accept-exceptions.py --zip WIZARD.zip --work DIR --plugin-repo URL --plugin-version V --toolchain DIR --udid U [--port 5568]
       [--prop key=value ...]
Stops what it started (the app by bundle id, the project's compile daemons) at the end.
"""
import argparse
import os
import re
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
ap.add_argument("--prop", action="append", default=[], help="extra key=value for local.properties")
a = ap.parse_args()

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "../../.."))
WORK = os.path.abspath(a.work)
P = os.path.join(WORK, "KmpHr")
HOME = os.path.expanduser("~")
EXE, BUNDLE = "KmpHr", "org.example.kmphr.hotreload"
APP = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/App.kt")
EXC = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/Exc.kt")
LOG = os.path.join(P, "shared/launch.log")
results = []


def clean(t):
    return t.replace(WORK, "<WORK>").replace(REPO, "<REPO>").replace(HOME, "<HOME>")


def say(line=""):
    print(clean(line), flush=True)


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    say(f"{'PASS' if ok else 'FAIL'} {name}" + (f" | {detail}" if detail else ""))


def edit(path, old, new):
    s = open(path).read()
    if old not in s:
        raise KeyError(f"{old!r} not in {path}")
    open(path, "w").write(s.replace(old, new, 1))


def gradle(*args, timeout=1800):
    t0 = time.time()
    r = subprocess.run([os.path.join(P, "gradlew"), "-p", P, *args, "--console=plain"], cwd=P, capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout + r.stderr, time.time() - t0


def pid():
    o = subprocess.run(["pgrep", "-f", f"Devices/{a.udid}/.*/{EXE}.app/{EXE}"], capture_output=True, text=True).stdout.split()
    return o[0] if o else None


def exc_lines():
    try:
        return [l.strip() for l in open(LOG, errors="replace").read().replace("\r", "\n").splitlines() if l.startswith("EXC ")]
    except OSError:
        return []


def wait_exc(want, timeout=25):
    t0 = time.time()
    while time.time() - t0 < timeout:
        ls = exc_lines()
        if ls and ls[-1] == want:
            return True, ls[-1]
        if pid() is None:
            break
        time.sleep(0.3)
    ls = exc_lines()
    return False, (ls[-1] if ls else "(no EXC line)")


def exc_source(tag, msg):
    return f'''package org.example.kmphr

class AppFailure(message: String) : Exception(message)

const val EXC_TAG = "{tag}"

// #80 acceptance: each kind throws from this function and is caught by catcher() below, both in the patched file.
fun thrower(kind: Int, n: Int): Int = when (kind) {{
    0 -> ("s" + n as Any) as Int
    1 -> {{
        val s: String? = if (n > -1) null else "x"
        s!!.length
    }}
    else -> throw AppFailure("{msg}-" + n)
}}

fun catcher(): String = (0..2).joinToString(",") {{ k ->
    try {{
        "ok" + thrower(k, 1)
    }} catch (e: ClassCastException) {{
        "cce"
    }} catch (e: NullPointerException) {{
        "npe"
    }} catch (e: AppFailure) {{
        "app:" + e.message
    }}
}}
'''


say(f"# #80 acceptance (exceptions thrown and caught in patched code), plugin {a.plugin_version}, {time.strftime('%F %T')}, simulator {a.udid}")
if os.path.exists(WORK):
    shutil.rmtree(WORK)
os.makedirs(WORK)
zipfile.ZipFile(a.zip).extractall(WORK)
os.chmod(os.path.join(P, "gradlew"), 0o755)
edit(os.path.join(P, "settings.gradle.kts"), "pluginManagement {\n    repositories {\n",
     f'pluginManagement {{\n    repositories {{\n        exclusiveContent {{ forRepository {{ maven {{ url = uri("{a.plugin_repo}") }} }}; filter {{ includeGroupByRegex("com\\\\.solufee\\\\.cmp(\\\\..*)?") }} }}\n')
edit(os.path.join(P, "shared/build.gradle.kts"), "plugins {\n", f'plugins {{\n    id("com.solufee.cmp.hotreload") version "{a.plugin_version}"\n')
open(os.path.join(P, "local.properties"), "w").write(
    f"cmp.hotreload.toolchainDir={a.toolchain}\ncmp.hotreload.simulatorUdid={a.udid}\ncmp.hotreload.port={a.port}\n" +
    "".join(f"{p}\n" for p in a.prop))
open(EXC, "w").write(exc_source("v1", "custom1"))
s = open(APP).read()
s = re.sub(r"(fun App\(\)\s*\{\n)", r'\1    println("EXC " + EXC_TAG + " " + catcher())\n', s, count=1)
open(APP, "w").write(s)
try:
    say("## 1. package, run")
    rc, out, dt = gradle("hotReloadPackage")
    check("package", rc == 0 and "package timing" in out, f"{dt:.0f}s")
    if rc != 0:
        say("\n".join(out.splitlines()[-30:]))
        sys.exit(1)
    rc, out, dt = gradle("hotReloadRun")
    ok, line = wait_exc("EXC v1 cce,npe,app:custom1-1")
    check("run: the three exceptions are caught in the packaged code", rc == 0 and ok, line)
    p0 = pid()
    say("## 2. thrower and catcher patched, three rounds")
    for n in (2, 3, 4):
        open(EXC, "w").write(exc_source(f"v{n}", f"custom{n}"))
        rc, out, dt = gradle("hotReload")
        for l in [l for l in out.splitlines() if re.match(r"^(compat:|sent |app loaded|patch unwind info)", l)]:
            say("  | " + l)
        ok, line = wait_exc(f"EXC v{n} cce,npe,app:custom{n}-1")
        check(f"round {n - 1}: HOT, same process, all three exceptions thrown and caught in patched code",
              rc == 0 and "compat: HOT" in out and ok and pid() == p0, f"{line} | pid {p0} -> {pid()} | {dt:.1f}s")
    log = open(LOG, errors="replace").read()
    crash = re.search(r"Uncaught[^\n]*|EXC_BAD[^\n]*|SIGSEGV|SIGABRT|terminating[^\n]*", log)
    check("no crash in launch.log; one app process throughout", not crash and pid() == p0, crash.group(0)[:200] if crash else f"pid {p0}")
except Exception as e:
    check(f"script error: {type(e).__name__}: {e}", False)
finally:
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
    gradle("hotReloadDaemonStop")
    say(f"stopped {BUNDLE} and the compile daemons; app running: {pid() is not None}")
failed = [n for n, ok in results if not ok]
say(f"summary: {len(results) - len(failed)}/{len(results)} PASS" + ("" if not failed else f"; FAILED: {failed}"))
sys.exit(1 if failed else 0)
