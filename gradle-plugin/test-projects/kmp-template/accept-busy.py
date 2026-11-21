#!/usr/bin/env python3
"""0.2.4 acceptance for #40: a patch the app does not apply because its main thread is busy (toolchain .8+).

The wizard template gets a main-thread timer (iosMain) that blocks the main thread for 8 s (native sleep, like a hang)
when a flag file appears, as example-gradle/accept-default-param.sh BUSY does. The runtime then logs
"HR reload: NOT applied: the main thread did not take the reload within … ms" and applies nothing.

  1  package, run; an edit -> HOT, applied on the main queue
  2  main thread blocked, an edit -> hotReload fails (non-zero exit) with "the app was busy, patch not applied";
     same PID, the old text stays on screen
  3  main thread free again, hotReload with no further edit -> the pending change is sent again (not "nothing
     changed") and shows
  4  hotReloadWatch -t: main thread blocked, an edit -> that run fails with the same message, the watch keeps
     watching; the next edit is applied with the earlier one; Ctrl-C stops the watch

Usage: accept-busy.py --zip WIZARD.zip --work DIR --plugin-repo URL --plugin-version V --toolchain DIR --udid U [--port 5568]
       [--prop key=value ...]
Stops what it started (the app by bundle id, the project's compile daemons, the watch by SIGINT) at the end.
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
BUSYKT = os.path.join(P, "shared/src/iosMain/kotlin/org/example/kmphr/BusyHook.kt")
LOG = os.path.join(P, "shared/launch.log")
FLAG = f"/tmp/hr-plugin-busy-{a.udid}"
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


def block_main():
    """Blocks the app's main thread for 8 s; returns the log position before it started."""
    pos = log_size()
    open(FLAG, "w").close()
    ok = wait_log(r"BUSY start", pos, 10)
    return pos, ok


BUSY_HOOK = f'''@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.example.kmphr

import platform.Foundation.NSTimer

private var busyHookInstalled = false

// #40 acceptance: when the flag file appears, block the main thread for 8 s (native sleep, like a hang).
fun installBusyHook() {{
    if (busyHookInstalled) return
    busyHookInstalled = true
    NSTimer.scheduledTimerWithTimeInterval(0.1, true) {{ _ ->
        if (platform.posix.access("{FLAG}", platform.posix.F_OK) == 0) {{
            platform.posix.remove("{FLAG}")
            println("BUSY start")
            platform.posix.usleep(8_000_000u)
            println("BUSY end")
        }}
    }}
}}
'''

say(f"# #40 acceptance (patch NOT applied while the main thread is busy), plugin {a.plugin_version}, {time.strftime('%F %T')}, simulator {a.udid}")
if os.path.exists(WORK):
    shutil.rmtree(WORK)
os.makedirs(WORK)
if os.path.exists(FLAG):
    os.remove(FLAG)
zipfile.ZipFile(a.zip).extractall(WORK)
os.chmod(os.path.join(P, "gradlew"), 0o755)
edit(os.path.join(P, "settings.gradle.kts"), "pluginManagement {\n    repositories {\n",
     f'pluginManagement {{\n    repositories {{\n        maven {{ url = uri("{a.plugin_repo}") }}\n')
edit(os.path.join(P, "shared/build.gradle.kts"), "plugins {\n", f'plugins {{\n    id("com.solufee.cmp.hotreload") version "{a.plugin_version}"\n')
open(os.path.join(P, "local.properties"), "w").write(
    f"cmp.hotreload.toolchainDir={a.toolchain}\ncmp.hotreload.simulatorUdid={a.udid}\ncmp.hotreload.port={a.port}\n" +
    "".join(f"{p}\n" for p in a.prop))
edit(APP, '                Text("Click me!")\n            }\n', '                Text("Click me!")\n            }\n            Text("Static: st-1")\n')
open(BUSYKT, "w").write(BUSY_HOOK)
mvc = open(MVC).read()
mvc = re.sub(r"(ComposeUIViewController\s*(?:\([^)]*\))?\s*\{\s*App\(\)\s*\})", r"\1.also { installBusyHook() }", mvc, count=1)
open(MVC, "w").write(mvc)
watch = None
# Issue #66: `axe describe-ui` turns on the simulator's accessibility switch (ApplicationAccessibilityEnabled,
# AccessibilityEnabled; ~1.8 s more at every app start). The shared helper saves both keys before the first describe-ui
# and writes them back when the script ends, also after a failure (absent keys are written back as NO).
AX_HELPER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../../example-gradle/tools/sim-ax-keys.sh")


def ax_keys(cmd):
    """Run example-gradle/tools/sim-ax-keys.sh save|restore for this simulator. Returns (ok, line)."""
    r = subprocess.run(["bash", AX_HELPER, cmd, a.udid], capture_output=True, text=True)
    line = (r.stdout.strip() or r.stderr.strip()).splitlines()[-1:] or [f"sim-ax-keys.sh {cmd}: no output"]
    return r.returncode == 0, line[0]


say(ax_keys("save")[1])
try:
    say("## 1. package, run, one edit")
    rc, out, dt = gradle("hotReloadPackage")
    check("package", rc == 0 and "package timing" in out, f"{dt:.0f}s")
    if rc != 0:
        say("\n".join(out.splitlines()[-30:]))
        sys.exit(1)
    rc, out, dt = gradle("hotReloadRun")
    check("run (KONAN_HR_RELOAD_ON_MAIN=1, KONAN_HR_MAIN_WAIT_MS=2000 passed to the app)", rc == 0 and "app started" in out and shows("Static: st-1"), f"pid {pid()}")
    p0 = pid()
    edit(APP, '"Static: st-1"', '"Static: st-2"')
    rc, out, dt = gradle("hotReload")
    for l in lines(out, r"^(compat:|sent |app loaded)"):
        say("  | " + l)
    check("an edit -> HOT, applied on the main queue, on screen", rc == 0 and "compat: HOT" in out and "on the main queue after" in out
          and shows("Static: st-2") and pid() == p0, f"{dt:.1f}s")

    say("## 2. main thread blocked for 8 s, an edit")
    edit(APP, '"Static: st-2"', '"Static: st-3"')
    pos, blocked = block_main()
    rc, out, dt = gradle("hotReload")
    for l in lines(out, r"^(compat:|sent |app loaded)|was busy|NOT applied"):
        say("  | " + l)
    busy = lines(out, r"the app was busy, patch not applied")
    check("hotReload fails with 'the app was busy, patch not applied' (non-zero exit)", blocked and rc != 0 and busy, (busy[:1] or ["-"])[0][:220])
    wait_log(r"BUSY end", pos, 15)
    since = log_since(pos)
    check("the runtime logged 'HR reload: NOT applied' and applied nothing; same PID; the old text stays",
          "HR reload: NOT applied" in since and "HR reload: ran" not in since and pid() == p0 and shows("Static: st-2", 5) and "Static: st-3" not in labels(),
          (re.findall(r"HR reload: NOT applied[^\n]*", since) or ["-"])[0][:160])

    say("## 3. main thread free again: hotReload without another edit")
    rc, out, dt = gradle("hotReload")
    for l in lines(out, r"^(compat:|sent |app loaded)|nothing changed"):
        say("  | " + l)
    check("the pending change is sent again (not 'nothing changed') and shows", rc == 0 and "nothing changed" not in out and "compat: HOT" in out
          and "on the main queue after" in out and shows("Static: st-3") and pid() == p0, f"{dt:.1f}s")

    say("## 4. hotReloadWatch -t")
    wlog = os.path.join(WORK, "watch.log")
    wf = open(wlog, "w")
    watch = subprocess.Popen([os.path.join(P, "gradlew"), "-p", P, "hotReloadWatch", "-t", "--console=plain"], cwd=P, stdout=wf,
                             stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, preexec_fn=lambda: signal.signal(signal.SIGINT, signal.SIG_DFL))

    def wtext():
        return open(wlog, errors="replace").read()

    def waiting_after(pos, timeout=240):
        t0 = time.time()
        while time.time() - t0 < timeout:
            if "Waiting for changes to input files" in wtext()[pos:]:
                return True
            time.sleep(0.5)
        return False

    check("watch: first run, then waits", waiting_after(0))
    mark = len(wtext())
    pos, blocked = block_main()
    edit(APP, '"Static: st-3"', '"Static: st-4"')
    w = waiting_after(mark)
    seg = wtext()[mark:]
    for l in lines(seg, r"was busy|BUILD|compat:"):
        say("  | " + l)
    check("watch: with the main thread blocked the run fails with 'the app was busy', the watch keeps watching",
          blocked and w and "the app was busy, patch not applied" in seg and "BUILD FAILED" in seg and watch.poll() is None)
    wait_log(r"BUSY end", pos, 15)
    mark = len(wtext())
    edit(APP, '"Static: st-4"', '"Static: st-5"')
    w = waiting_after(mark)
    seg = wtext()[mark:]
    check("watch: the next edit is applied (with the one that was not)", w and "compat: HOT" in seg and "BUILD SUCCESSFUL" in seg
          and shows("Static: st-5") and pid() == p0)
    watch.send_signal(signal.SIGINT)
    try:
        watch.wait(timeout=60)
        stopped = True
    except subprocess.TimeoutExpired:
        stopped = False
    check("watch: Ctrl-C (SIGINT) stops it", stopped, f"exit {watch.returncode}")
    watch = None
    log = open(LOG, errors="replace").read()
    crash = re.search(r"Uncaught|EXC_|SIGSEGV|SIGBUS|IrLinkageError", log)
    check("no crash in launch.log; one app process throughout", not crash and pid() == p0, crash.group(0) if crash else f"pid {p0}")
except Exception as e:
    check(f"script error: {type(e).__name__}: {e}", False)
finally:
    if watch is not None and watch.poll() is None:
        watch.send_signal(signal.SIGINT)
        try:
            watch.wait(timeout=60)
        except subprocess.TimeoutExpired:
            watch.kill()
    if os.path.exists(FLAG):
        os.remove(FLAG)
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
    gradle("hotReloadDaemonStop")
    say(f"stopped {BUNDLE} and the compile daemons; app running: {pid() is not None}")
    ax_ok, ax_line = ax_keys("restore")
    check("simulator accessibility switch written back (sim-ax-keys.sh restore: read back == value before describe-ui)", ax_ok, ax_line)
failed = [n for n, ok in results if not ok]
say(f"summary: {len(results) - len(failed)}/{len(results)} PASS" + ("" if not failed else f"; FAILED: {failed}"))
sys.exit(1 if failed else 0)
