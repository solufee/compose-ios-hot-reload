#!/usr/bin/env python3
"""0.2.3 acceptance for #7 (no change = "nothing changed", exit 0) and #8 (declared inputs, hotReloadWatch -t).

On the KMP wizard template (sources unchanged, plus a static Text so new code shows without taps):
  one-shot   hotReload with no edit: exit 0, "nothing changed", nothing sent; an edit: HOT on screen;
             a compile error: the task fails; the fix: HOT again
  watch      ./gradlew hotReloadWatch -t (Gradle continuous build):
             first run: "nothing changed", no failure; saving an edit: HOT on screen without taps;
             touching a file without changing it: a run that sends nothing; a resource edit: WARM;
             a compile error: that run fails, the watch keeps going, the fix reloads;
             the configuration cache is reused on the re-runs; Ctrl-C (SIGINT to the Gradle client) stops it;
             afterwards a plain hotReload still works with the same compile daemons.

Usage: accept-watch.py --zip WIZARD.zip --work DIR --plugin-repo URL --toolchain DIR --udid U [--plugin-version 0.2.3]
"""
import argparse
import json
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
ap.add_argument("--plugin-version", default="0.2.3")
ap.add_argument("--toolchain", required=True)
ap.add_argument("--udid", required=True)
ap.add_argument("--port", default="5568")
ap.add_argument("--prop", action="append", default=[], help="extra key=value for local.properties (e.g. tools of a local toolchain view)")
a = ap.parse_args()

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "../../.."))
WORK = os.path.abspath(a.work)
P = os.path.join(WORK, "KmpHr")
HOME = os.path.expanduser("~")
EXE, BUNDLE = "KmpHr", "org.example.kmphr.hotreload"
LOG = os.path.join(P, "shared/launch.log")
APP = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/App.kt")
GREET = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/GreetingUtil.kt")
DRAWABLE = os.path.join(P, "shared/src/commonMain/composeResources/drawable/compose-multiplatform.xml")
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


def labels():
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
        if any(text in l for l in labels()):
            return True
        time.sleep(0.3)
    return False


def pid():
    o = subprocess.run(["pgrep", "-f", f"Devices/{a.udid}/.*/{EXE}.app/{EXE}"], capture_output=True, text=True).stdout.split()
    return o[0] if o else None


def kaldo_lines():
    return len(re.findall(r"KotlinSymbolExternalizer", open(LOG, errors="replace").read())) if os.path.exists(LOG) else 0


# ---------------------------------------------------------------- setup (TRYOUT section 7 + a static Text)
say(f"# #7/#8 acceptance, {time.strftime('%F %T')}, plugin {a.plugin_version}, simulator {a.udid}")
if os.path.exists(WORK):
    shutil.rmtree(WORK)
os.makedirs(WORK)
zipfile.ZipFile(a.zip).extractall(WORK)
os.chmod(os.path.join(P, "gradlew"), 0o755)
edit(os.path.join(P, "settings.gradle.kts"), "pluginManagement {\n    repositories {\n",
     f'pluginManagement {{\n    repositories {{\n        maven {{ url = uri("{a.plugin_repo}") }}\n')
edit(os.path.join(P, "shared/build.gradle.kts"), "plugins {\n", f'plugins {{\n    id("com.solufee.cmp.hotreload") version "{a.plugin_version}"\n')
open(os.path.join(P, "local.properties"), "w").write(
    f"cmp.hotreload.toolchainDir={a.toolchain}\ncmp.hotreload.simulatorUdid={a.udid}\ncmp.hotreload.port={a.port}\n" + "".join(f"{p}\n" for p in a.prop))
edit(APP, '                Text("Click me!")\n            }\n', '                Text("Click me!")\n            }\n            Text("Static: st-1")\n')
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
    rc, out, dt = gradle("hotReloadPackage")
    check("package", rc == 0 and "package timing" in out, f"{dt:.0f}s")
    rc, out, dt = gradle("hotReloadRun")
    check("run", rc == 0 and "app started" in out and shows("Static: st-1"), f"pid {pid()}")
    p0 = pid()

    say("## one-shot hotReload")
    k0 = kaldo_lines()
    rc, out, dt = gradle("hotReload")
    check("no edit: exit 0, 'nothing changed', nothing sent", rc == 0 and "nothing changed (1 hot module checked); nothing sent" in out
          and "sent " not in out and kaldo_lines() == k0 and pid() == p0, f"{dt:.1f}s; " + "; ".join(l for l in out.splitlines() if "nothing changed" in l))
    edit(APP, '"Static: st-1"', '"Static: st-2"')
    rc, out, dt = gradle("hotReload")
    check("an edit: HOT, on screen", rc == 0 and "compat: HOT" in out and "app loaded the patch" in out and shows("Static: st-2") and pid() == p0, f"{dt:.1f}s")
    edit(GREET, "fun sayHello(", "fun broken( = 1\n\nfun sayHello(")
    rc, out, dt = gradle("hotReload")
    errs = [l for l in out.splitlines() if re.search(r"\be: |error:", l)]
    check("a compile error: the task fails with the compiler's message", rc != 0 and errs and pid() == p0, (errs[:1] or ["-"])[0][:160])
    edit(GREET, "fun broken( = 1\n\n", "")
    rc, out, dt = gradle("hotReload")
    check("after the fix: the next hotReload succeeds", rc == 0 and ("compat: HOT" in out or "nothing changed" in out), f"{dt:.1f}s")

    say("## hotReloadWatch -t")
    wlog = os.path.join(WORK, "watch.log")
    wf = open(wlog, "w")
    # Like a terminal: SIGINT handled by default (a script started in the background inherits it ignored).
    watch = subprocess.Popen([os.path.join(P, "gradlew"), "-p", P, "hotReloadWatch", "-t", "--console=plain"], cwd=P, stdout=wf,
                             stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, preexec_fn=lambda: signal.signal(signal.SIGINT, signal.SIG_DFL))

    def wtext():
        return open(wlog, errors="replace").read()

    def wait_for(pattern, after, timeout=180):
        t0 = time.time()
        while time.time() - t0 < timeout:
            m = [x for x in re.finditer(pattern, wtext()) if x.start() >= after]
            if m:
                return m[0]
            time.sleep(0.5)
        return None

    def waiting_after(pos, timeout=240):
        return wait_for(r"Waiting for changes to input files", pos, timeout)

    first = waiting_after(0)
    t = wtext()
    check("watch: first run succeeds ('nothing changed'), then waits", first is not None and "nothing changed" in t and "BUILD SUCCESSFUL" in t
          and "hotReloadWatch: watching" in t, "; ".join(l for l in t.splitlines() if "hotReloadWatch:" in l)[:200])
    mark = len(wtext())
    edit(APP, '"Static: st-2"', '"Static: st-3"')
    hot = waiting_after(mark)
    seg = wtext()[mark:]
    check("watch: a saved edit -> HOT, on screen without taps", hot and "compat: HOT" in seg and shows("Static: st-3") and pid() == p0,
          "; ".join(l for l in seg.splitlines() if "compat:" in l or "Change detected" in l or "modified" in l)[:220])
    mark = len(wtext())
    k0 = kaldo_lines()
    os.utime(APP, None)
    w = waiting_after(mark, timeout=60)
    seg = wtext()[mark:]
    check("watch: touching a file without changing it sends nothing", (w is None or "sent " not in seg) and kaldo_lines() == k0 and pid() == p0,
          "re-run: " + ("nothing changed" if "nothing changed" in seg else ("no run" if w is None else "other")))
    mark = len(wtext())
    edit(DRAWABLE, 'android:fillColor="#FF6075F2"', 'android:fillColor="#FF00A000"') if 'android:fillColor="#FF6075F2"' in open(DRAWABLE).read() \
        else open(DRAWABLE, "a").write("<!-- edited -->\n")
    w = waiting_after(mark, timeout=300)
    seg = wtext()[mark:]
    p1 = pid()
    check("watch: a Compose resource edit -> WARM (compose-resources)", w and "WARM compose-resources" in seg and "WARM OK" in seg and p1 and p1 != p0,
          "; ".join(l for l in seg.splitlines() if l.startswith("WARM"))[:220])
    p0 = p1
    mark = len(wtext())
    edit(GREET, "fun sayHello(", "fun broken( = 1\n\nfun sayHello(")
    w = waiting_after(mark)
    seg = wtext()[mark:]
    check("watch: a compile error fails that run, the watch keeps going", w and "BUILD FAILED" in seg and watch.poll() is None, "")
    mark = len(wtext())
    edit(GREET, "fun broken( = 1\n\n", "")
    edit(GREET, '"Hello, ', '"Hello watch, ')
    w = waiting_after(mark)
    seg = wtext()[mark:]
    check("watch: the fix reloads (HOT)", w and "compat: HOT" in seg and "BUILD SUCCESSFUL" in seg and pid() == p0, "")
    t = wtext()
    reuse = t.count("Reusing configuration cache")
    check("watch: configuration cache reused on the re-runs, no problems", reuse >= 4 and "problems were found" not in t, f"{reuse} reuses")
    watch.send_signal(signal.SIGINT)
    try:
        watch.wait(timeout=60)
        stopped = True
    except subprocess.TimeoutExpired:
        stopped = False
    check("watch: Ctrl-C (SIGINT) stops it", stopped, f"exit {watch.returncode}")
    watch = None
    edit(APP, '"Static: st-3"', '"Static: st-4"')
    rc, out, dt = gradle("hotReload")
    check("after the watch: a plain hotReload works", rc == 0 and "compat: HOT" in out and shows("Static: st-4"), f"{dt:.1f}s")
except Exception as e:
    check(f"script error: {type(e).__name__}: {e}", False)
finally:
    if watch is not None and watch.poll() is None:
        watch.send_signal(signal.SIGINT)
        try:
            watch.wait(timeout=60)
        except subprocess.TimeoutExpired:
            watch.kill()
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
    gradle("hotReloadDaemonStop")
    say(f"stopped {BUNDLE} and the compile daemons; app running: {pid() is not None}")
    time.sleep(3)
    locks = subprocess.run(["lsof", "+D", os.path.join(P, ".gradle")], capture_output=True, text=True).stdout
    held = [l for l in locks.splitlines() if l.endswith(".lock")]
    check("no Gradle build is left running in the project after Ctrl-C (no build lock held)", not held, f"{len(held)} locks held")
    ax_ok, ax_line = ax_keys("restore")
    check("simulator accessibility switch written back (sim-ax-keys.sh restore: read back == value before describe-ui)", ax_ok, ax_line)
failed = [n for n, ok in results if not ok]
say(f"summary: {len(results) - len(failed)}/{len(results)} PASS" + ("" if not failed else f"; FAILED: {failed}"))
sys.exit(1 if failed else 0)
