#!/usr/bin/env python3
"""Issue #78 regression: a HOT patch of a file that declares a class keeps instances of that class usable by the new code.

The wizard template gets Store78.kt (class Store78 + describe78(x: Any?) = (x as Store78).label()) and holders that the
patch does not rebuild (every patch recompiles the whole module, and objects declared in it start over, LIMITATIONS 15).
Instances of Store78 created BEFORE the patch:
  comp   remembered in the composition (reachable from Compose's library globals and the UIKit view controller)
  objc   only in NSThread.mainThread.threadDictionary (reachable only through an external reference held by
         Objective-C)
and one created after the patch by code that is not edited:
  fresh  Holder78.kt makeStore78()
The app turns automatic GC off (MainViewController, kotlin.native.runtime.GC: no timer, no heap trigger): globals and
external references registered by a thread reach the runtime's registries only when a GC publishes them, and the
issue showed up when the patch came before the first GC after a start (Issue #78, runtime StateTransfer.cpp).
After each body edit of Store78.label() (HOT) the screen must show the new text for all three, never "CCE"
(ClassCastException: 'Store78 cannot be cast to Store78') or "ERR", and the app must keep its PID.

Usage: accept-typeinfo.py --zip WIZARD.zip --work DIR --plugin-repo URL --plugin-version V --toolchain DIR --udid U [--port 5568]
       [--edits 10] [--prop key=value ...]
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
ap.add_argument("--edits", type=int, default=10)
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
STORE = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/Store78.kt")
HOLDER = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/Holder78.kt")
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




STORE_KT = '''package org.example.kmphr

// Issue #78: the HOT edits change only the body of label().
class Store78(val name: String) {
    fun label(): String = "V0-$name"
}

// Cast in the patched file: it is checked against this file's (new) class.
fun describe78(x: Any?): String = try { (x as Store78).label() } catch (e: ClassCastException) { "CCE" } catch (e: Throwable) { "ERR-" + e::class.simpleName }
'''

HOLDER_KT = '''package org.example.kmphr

// Issue #78: not edited during the test.
class Box78(val any: Any)

fun makeStore78(n: String): Any = Store78(n)

expect fun objc78(): Any?
'''

OBJC_KT = '''package org.example.kmphr

import platform.Foundation.valueForKey

actual fun objc78(): Any? = platform.Foundation.NSThread.mainThread.threadDictionary.valueForKey("r78")
'''

MVC_KT = '''package org.example.kmphr

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.setValue

// Issue #78 acceptance: no automatic GC, so nothing a thread registered gets published before the patch.
@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
fun MainViewController() = run {
    kotlin.native.runtime.GC.autotune = false
    kotlin.native.runtime.GC.targetHeapBytes = 4L shl 30
    kotlin.native.runtime.GC.regularGCInterval = kotlin.time.Duration.parse("1h")
    platform.Foundation.NSThread.mainThread.threadDictionary.setValue(Store78("objc"), forKey = "r78")
    ComposeUIViewController { App() }
}
'''

APP_SNIPPET = '''            val box78 = androidx.compose.runtime.remember { Box78(Store78("comp")) }
            val r78 = "R78 comp=${describe78(box78.any)} objc=${describe78(objc78())} fresh=${describe78(makeStore78("fresh"))}"
            println(r78)
            Text(r78)
'''


def expect(v):
    return f"R78 comp=V{v}-comp objc=V{v}-objc fresh=V{v}-fresh"


say(f"# #78 acceptance (HOT patch of a file declaring a class with live instances), plugin {a.plugin_version}, {time.strftime('%F %T')}, simulator {a.udid}")
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
edit(APP, '                Text("Click me!")\n            }\n', '                Text("Click me!")\n            }\n' + APP_SNIPPET)
open(STORE, "w").write(STORE_KT)
open(HOLDER, "w").write(HOLDER_KT)
open(MVC, "w").write(MVC_KT)
open(os.path.join(os.path.dirname(MVC), "Objc78.kt"), "w").write(OBJC_KT)
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
    check("run: the three instances answer V0", rc == 0 and "app started" in out and shows(expect(0)), f"pid {pid()}")
    p0 = pid()
    say(f"## 2. {a.edits} body edits of Store78.label() (HOT)")
    for v in range(1, a.edits + 1):
        edit(STORE, f'"V{v - 1}-$name"', f'"V{v}-$name"')
        pos = log_size()
        rc, out, dt = gradle("hotReload")
        hot = "compat: HOT" in out
        seen = rc == 0 and shows(expect(v), 20)
        got = re.findall(r"R78 [^\n]*", log_since(pos))
        check(f"edit {v}: HOT, same PID, all three show V{v}", rc == 0 and hot and seen and pid() == p0,
              f"{dt:.1f}s; last R78 line: {(got or ['-'])[-1]}")
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
