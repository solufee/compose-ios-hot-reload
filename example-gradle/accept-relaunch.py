#!/usr/bin/env python3
"""#62 acceptance: the warm restart's two install paths keep the app's state, and it recovers when the app is gone.

  1. package, then `hotReloadRun --args=--hr-state=2,3` (the app writes Documents/hr-state.json: page 2, count 3)
  2. in-place update: an app-module edit (WARM) -> "executable replaced in the installed app", the same data
     container (hr-state.json not written by the plugin), the new app on page 2 count 3,
     installed executable sha256 == packaged executable sha256
  3. forced reinstall: the installed Info.plist is changed by hand -> "uninstall + install (Info.plist changed)",
     the plugin carries hr-state.json into the new install, the new app on page 2 count 3, installed executable and
     Info.plist sha256 == packaged
  4. data container deleted (app stopped, container removed) -> next warm restart installs afresh, no error
  5. app uninstalled by hand -> next warm restart installs afresh, no error
  6. the next warm restart after that updates in place again
  7. accessibility note: printed exactly once by hotReloadRun and by each warm restart when the simulator reads
     ApplicationAccessibilityEnabled = 1, never when it reads anything else (read here only; the setting is not changed)

Edits append top-level properties to app/src/main/kotlin/main.kt; the file is restored at the end, the app (by bundle
id) and the project's compile daemons are stopped.

Usage: UDID=<udid> python3 accept-relaunch.py    (local.properties: toolchainDir, simulatorUdid, port)
"""
import glob
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
UDID = os.environ["UDID"]
BUNDLE = "com.example.composehr"
APP = os.path.join(HERE, "app/src/main/kotlin/main.kt")
LOG = os.path.join(HERE, "launch.log")
HOME = os.path.expanduser("~")
REPO = os.path.dirname(HERE)
results = []


def clean(t):
    return t.replace(REPO, "<REPO>").replace(HOME, "<HOME>")


def say(line=""):
    print(clean(line), flush=True)


def check(name, ok, detail=""):
    results.append(bool(ok))
    say(f"{'PASS' if ok else 'FAIL'} {name}" + (f" | {detail}" if detail else ""))


def gradle(*args, timeout=1800):
    t0 = time.time()
    r = subprocess.run([os.path.join(HERE, "gradlew"), "-p", HERE, *args, "--console=plain"], cwd=HERE,
                       capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout + r.stderr, time.time() - t0


def simctl(*args):
    return subprocess.run(["xcrun", "simctl", *args], capture_output=True, text=True)


def container(kind):
    r = simctl("get_app_container", UDID, BUNDLE, kind)
    return r.stdout.strip() if r.returncode == 0 else None


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest() if path and os.path.isfile(path) else None


def packaged():
    return glob.glob(os.path.join(HERE, "build/hotreload/*.app"))[0]


def same_files(name):
    """sha256 of the installed and the packaged copy of one bundle file."""
    inst = container("app")
    a = sha(os.path.join(inst, name)) if inst else None
    b = sha(os.path.join(packaged(), name))
    return a is not None and a == b, f"installed {name} sha256 {(a or '-')[:16]} packaged {(b or '-')[:16]}"


def exe_name():
    return os.path.basename(packaged())[:-4]


def pick(out, *pats):
    return " / ".join(l.strip() for l in out.splitlines() if any(p in l for p in pats))


def read_log():
    try:
        return open(LOG, errors="replace").read()
    except OSError:
        return ""


def wait_log(pattern, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        if re.search(pattern, read_log()):
            return True
        time.sleep(0.3)
    return False


def state_file():
    d = container("data")
    p = os.path.join(d, "Documents/hr-state.json") if d else None
    return open(p).read().strip() if p and os.path.isfile(p) else None


n = [0]


AX = simctl("spawn", UDID, "defaults", "read", "com.apple.Accessibility", "ApplicationAccessibilityEnabled")
AX_ON = AX.returncode == 0 and AX.stdout.strip() == "1"
AX_LINE = "this simulator has application accessibility on"
ax_counts = []


def ax_note(what, out):
    ax_counts.append((what, out.count(AX_LINE)))


def warm_edit():
    n[0] += 1
    with open(APP, "a") as f:
        f.write(f"\nval relaunchCheck{n[0]}: Int = {n[0]}\n")
    r = gradle("hotReload")
    ax_note(f"warm restart {n[0]}", r[1])
    return r


orig = open(APP).read()
say(f"# #62 acceptance (install paths and state), {time.strftime('%F %T')}, simulator {UDID}")
try:
    rc, out, dt = gradle("hotReloadPackage")
    check("package", rc == 0, f"{dt:.0f}s")
    simctl("terminate", UDID, BUNDLE)
    rc, out, dt = gradle("hotReloadRun", "--args=--hr-state=2,3")
    ax_note("hotReloadRun", out)
    check("run with --hr-state=2,3", rc == 0 and wait_log(r"RENDER_APP .*count=3 page=2"), pick(out, "install:", "app started"))
    time.sleep(1.5)
    check("the app saved its state", state_file() == '{"page":2,"count":3}', f"Documents/hr-state.json = {state_file()}")

    say("## 2. in-place update")
    data_before = container("data")
    rc, out, dt = warm_edit()
    check("warm restart OK", rc == 0 and "WARM OK" in out and "WARM top-level-property" in out, pick(out, "WARM OK"))
    check("executable replaced in the installed app", "install: executable replaced in the installed app" in out, pick(out, "install:"))
    check("state carried: the app's data container kept (same path, state file not rewritten by the plugin)",
          container("data") == data_before and "into the new install" not in out and state_file() == '{"page":2,"count":3}',
          f"data container unchanged={container('data') == data_before}, Documents/hr-state.json = {state_file()}")
    check("the new app is on page 2, count 3", wait_log(r"RENDER_APP .*count=3 page=2"), pick(out, "warm: restoring"))
    ok, d = same_files(exe_name())
    check("installed executable == packaged executable", ok, d)

    say("## 3. forced reinstall (installed Info.plist changed by hand)")
    with open(os.path.join(container("app"), "Info.plist"), "ab") as f:
        f.write(b"\n")
    data_before = container("data")
    rc, out, dt = warm_edit()
    check("warm restart OK", rc == 0 and "WARM OK" in out, pick(out, "WARM OK"))
    check("uninstall + install because Info.plist changed", "install: uninstall + install" in out and "Info.plist changed" in out, pick(out, "install:"))
    check("state carried: the plugin wrote hr-state.json into the new install",
          "restored Documents/hr-state.json" in out and container("data") != data_before and state_file() == '{"page":2,"count":3}',
          f"{pick(out, 'restored Documents')}; Documents/hr-state.json = {state_file()}")
    check("the new app is on page 2, count 3", wait_log(r"RENDER_APP .*count=3 page=2"), pick(out, "warm: restoring"))
    ok, d = same_files(exe_name())
    check("installed executable == packaged executable", ok, d)
    ok, d = same_files("Info.plist")
    check("installed Info.plist == packaged Info.plist", ok, d)

    say("## 4. data container deleted")
    simctl("terminate", UDID, BUNDLE)
    d = container("data")
    shutil.rmtree(d)
    check("data container removed", not os.path.exists(d), clean(d))
    rc, out, dt = warm_edit()
    check("warm restart OK, no error", rc == 0 and "WARM OK" in out, pick(out, "WARM OK", "rror"))
    check("installed afresh (data container gone)", "install: uninstall + install" in out and "data container is gone" in out, pick(out, "install:"))
    check("the app runs (no saved state: first page)", wait_log(r"RENDER_APP .*count=0 page=0"), pick(out, "warm: the app saved no state"))
    ok, d = same_files(exe_name())
    check("installed executable == packaged executable", ok, d)

    say("## 5. app uninstalled by hand")
    r = simctl("uninstall", UDID, BUNDLE)
    check("uninstalled", r.returncode == 0 and container("app") is None)
    rc, out, dt = warm_edit()
    check("warm restart OK, no error", rc == 0 and "WARM OK" in out, pick(out, "WARM OK", "rror"))
    check("installed afresh (app not installed)", "install: uninstall + install" in out and "the app is not installed" in out, pick(out, "install:"))
    check("the app runs", wait_log(r"RENDER_APP .*count=0 page=0"))
    ok, d = same_files(exe_name())
    check("installed executable == packaged executable", ok, d)

    say("## 6. back to the in-place update")
    rc, out, dt = warm_edit()
    check("warm restart OK", rc == 0 and "WARM OK" in out, pick(out, "WARM OK"))
    check("executable replaced in the installed app", "install: executable replaced in the installed app" in out, pick(out, "install:"))
    ok, d = same_files(exe_name())
    check("installed executable == packaged executable", ok, d)

    say("## 7. accessibility note")
    want = 1 if AX_ON else 0
    check(f"simulator reads ApplicationAccessibilityEnabled = {AX.stdout.strip() or '-'} (exit {AX.returncode}): "
          f"the note {'exactly once' if want else 'never'} per run",
          ax_counts and all(c == want for _, c in ax_counts), ", ".join(f"{w}: {c}" for w, c in ax_counts))
finally:
    open(APP, "w").write(orig)
    simctl("terminate", UDID, BUNDLE)
    gradle("hotReloadDaemonStop")
    say("# app/src/main/kotlin/main.kt restored; app and compile daemons stopped")

say(f"summary: {sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
