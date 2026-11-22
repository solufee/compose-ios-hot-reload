#!/usr/bin/env python3
"""Issue #80 regression: an exception that unwinds through hot reload patch code is caught, the app keeps running.

The wizard template gets Eh80.kt (edited by the test) where a called function throws and its caller catches:
  cce    a failing cast (thrown by the runtime's downcast)        -> CCE
  npe    `!!` on null (thrown by the runtime)                      -> NPE
  my     a custom exception class declared in the patched file    -> MY
  chain  thrown two patched functions down (thrower80 <- mid80)   -> CHAIN
A throw caught in the same function becomes a jump and needs no unwinding, so every case goes through a call.
After each edit (HOT) the screen must show `EH80 v=<n> cce=CCE npe=NPE my=MY chain=CHAIN`, same PID, no crash.
Without the fix (no FDE registered for patch code) the first edit ends the app ("Uncaught Kotlin exception").

Usage: accept-eh-patch.py --zip WIZARD.zip --work DIR --plugin-repo URL --plugin-version V --toolchain DIR --udid U [--port 5568]
       [--edits 5] [--prop key=value ...]
"""
import argparse
import os
import re
import shutil
import signal
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
ap.add_argument("--edits", type=int, default=5)
ap.add_argument("--prop", action="append", default=[], help="extra key=value for local.properties (e.g. tools of a local toolchain)")
a = ap.parse_args()

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "../../.."))
WORK = os.path.abspath(a.work)
P = os.path.join(WORK, "KmpHr")
HOME = os.path.expanduser("~")
EXE, BUNDLE = "KmpHr", "org.example.kmphr.hotreload"
APP = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/App.kt")
MVC = os.path.join(P, "shared/src/iosMain/kotlin/org/example/kmphr/MainViewController.kt")
EH = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/Eh80.kt")
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


def lines(out, pattern):
    return [l for l in out.splitlines() if re.search(pattern, l)]


def labels():
    import json
    r = subprocess.run(["axe", "describe-ui", "--udid", a.udid], capture_output=True, text=True)
    try:
        d = json.loads(r.stdout) if r.returncode == 0 else []
    except ValueError:
        d = []

    def walk(ns):
        for n in ns:
            yield n
            yield from walk(n.get("children", []))
    return [n.get("AXLabel") or "" for n in walk(d if isinstance(d, list) else [d])]


def shows(text, timeout=25):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if text in labels():
            return True
        time.sleep(0.3)
    return False


def pid():
    o = subprocess.run(["pgrep", "-f", f"Devices/{a.udid}/.*/{EXE}.app/{EXE}"], capture_output=True, text=True).stdout.split()
    return o[0] if o else None


def log_size():
    return os.path.getsize(LOG) if os.path.exists(LOG) else 0


def log_since(pos):
    with open(LOG, errors="replace") as f:
        f.seek(pos)
        return f.read().replace("\r", "\n")


def wait_log(pattern, pos, timeout=30):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if re.search(pattern, log_since(pos)):
            return True
        time.sleep(0.2)
    return False




EH_KT = '''package org.example.kmphr

class Eh80Box(val v: String)

class Eh80Error(message: String) : Exception(message)

fun thrower80(kind: Int, tag: String): String {
    val any: Any = tag
    return when (kind) {
        0 -> (any as Eh80Box).v
        1 -> tag.takeIf { it.isEmpty() }!!
        else -> throw Eh80Error("custom $tag")
    }
}

fun mid80(tag: String): String = thrower80(2, tag) + "-mid"

fun caught80(kind: Int, tag: String): String = try {
    thrower80(kind, tag)
} catch (e: ClassCastException) {
    "CCE"
} catch (e: NullPointerException) {
    "NPE"
} catch (e: Eh80Error) {
    "MY"
}

fun chain80(tag: String): String = try { mid80(tag) } catch (e: Eh80Error) { "CHAIN" }

fun eh80Line(): String {
    val v = "v0"
    return "EH80 v=$v cce=${caught80(0, v)} npe=${caught80(1, v)} my=${caught80(2, v)} chain=${chain80(v)}"
}
'''

APP_SNIPPET = '''            val eh80 = eh80Line()
            println(eh80)
            Text(eh80)
'''


def expect(v):
    return f"EH80 v=v{v} cce=CCE npe=NPE my=MY chain=CHAIN"


say(f"# #80 acceptance (exceptions unwinding through hot reload patch code), plugin {a.plugin_version}, {time.strftime('%F %T')}, simulator {a.udid}")
if os.path.exists(WORK):
    shutil.rmtree(WORK)
os.makedirs(WORK)
zipfile.ZipFile(a.zip).extractall(WORK)
os.chmod(os.path.join(P, "gradlew"), 0o755)
edit(os.path.join(P, "settings.gradle.kts"), "pluginManagement {\n    repositories {\n",
     f'pluginManagement {{\n    repositories {{\n        maven {{ url = uri("{a.plugin_repo}") }}\n')
edit(os.path.join(P, "shared/build.gradle.kts"), "plugins {\n", f'plugins {{\n    id("com.solufee.cmp.hotreload") version "{a.plugin_version}"\n')
open(os.path.join(P, "local.properties"), "w").write(
    f"cmp.hotreload.toolchainDir={a.toolchain}\ncmp.hotreload.simulatorUdid={a.udid}\ncmp.hotreload.port={a.port}\n" +
    "".join(f"{p}\n" for p in a.prop))
edit(APP, '                Text("Click me!")\n            }\n', '                Text("Click me!")\n            }\n' + APP_SNIPPET)
open(EH, "w").write(EH_KT)
AX_HELPER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../../example-gradle/tools/sim-ax-keys.sh")


def ax_keys(cmd):
    r = subprocess.run(["bash", AX_HELPER, cmd, a.udid], capture_output=True, text=True)
    line = (r.stdout.strip() or r.stderr.strip()).splitlines()[-1:] or [f"sim-ax-keys.sh {cmd}: no output"]
    return r.returncode == 0, line[0]


say(ax_keys("save")[1])
try:
    say("## 1. package, run")
    rc, out, dt = gradle("hotReloadPackage")
    check("package", rc == 0 and "package timing" in out, f"{dt:.0f}s")
    if rc != 0:
        say("\n".join(out.splitlines()[-30:]))
        sys.exit(1)
    rc, out, dt = gradle("hotReloadRun")
    check("run: the base app answers EH80 v=v0 with all four catches", rc == 0 and "app started" in out and shows(expect(0)), f"pid {pid()}")
    p0 = pid()
    say(f"## 2. {a.edits} edits of Eh80.kt (HOT)")
    for v in range(1, a.edits + 1):
        edit(EH, f'val v = "v{v - 1}"', f'val v = "v{v}"')
        pos = log_size()
        rc, out, dt = gradle("hotReload")
        hot = "compat: HOT" in out
        seen = rc == 0 and shows(expect(v), 20)
        got = re.findall(r"EH80 [^\n]*", log_since(pos))
        check(f"edit {v}: HOT, same PID, shows {expect(v)}", rc == 0 and hot and seen and pid() == p0,
              f"{dt:.1f}s; last EH80 line: {(got or ['-'])[-1]}")
        if pid() != p0:
            break
    log = open(LOG, errors="replace").read()
    crash = re.search(r"Uncaught|EXC_|SIGSEGV|SIGBUS|IrLinkageError", log)
    check("no crash in launch.log; one app process throughout", not crash and pid() == p0, crash.group(0) if crash else f"pid {p0}")
except Exception as e:
    check(f"script error: {type(e).__name__}: {e}", False)
finally:
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
    gradle("hotReloadDaemonStop")
    say(f"stopped {BUNDLE} and the compile daemons; app running: {pid() is not None}")
    ax_ok, ax_line = ax_keys("restore")
    check("simulator accessibility switch written back", ax_ok, ax_line)
failed = [n for n, ok in results if not ok]
say(f"summary: {len(results) - len(failed)}/{len(results)} PASS" + ("" if not failed else f"; FAILED: {failed}"))
sys.exit(1 if failed else 0)
