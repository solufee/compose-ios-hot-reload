#!/usr/bin/env python3
"""#60 stage 2 item 0: does AXe still work with the simulator's ApplicationAccessibilityEnabled = 0, and does using
it (describe-ui, tap) turn the setting back on? Runs on a throwaway device it creates and deletes; the shared
simulators are not touched. Needs a packaged example (build/ComposeHR.app) and TOOLCHAIN_ENV sourced by the caller.
Usage: ax-axe-check.py <out-dir> [device-type] [runtime]   (AX_NO_WRITE=1: leave the key as the fresh device has it;
AX_PRESET_SHARED=1: first set both keys to 1 like the shared simulators, then write only ApplicationAccessibilityEnabled=0;
AX_WRITE_BOTH=1: write AccessibilityEnabled=0 as well; AX_REBOOT=1: shut down and boot the device after writing;
AX_TAP_ONLY=1: no describe-ui, tap fixed points (iPhone 18 Pro, example app page 0/1) to see what tap alone does)"""
import os, re, subprocess, sys, time
HERE = os.path.dirname(os.path.abspath(__file__)); EX = os.path.abspath(os.path.join(HERE, "..", ".."))
out = os.path.abspath(sys.argv[1]); os.makedirs(out, exist_ok=True)
DT = sys.argv[2] if len(sys.argv) > 2 else "com.apple.CoreSimulator.SimDeviceType.iPhone-18-Pro"
RT = sys.argv[3] if len(sys.argv) > 3 else "com.apple.CoreSimulator.SimRuntime.iOS-27-2"
REPORT = open(os.path.join(out, "result.txt"), "w")
def say(*a):
    s = " ".join(str(x) for x in a); print(s); REPORT.write(s + "\n"); REPORT.flush()
def sh(*cmd, check=True):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if check and r.returncode: raise RuntimeError(f"{cmd[:4]} rc={r.returncode} {r.stderr.strip()[:300]}")
    return r
udid = sh("xcrun", "simctl", "create", "hr60-axe", DT, RT).stdout.strip()
os.environ["UDID"] = udid
sys.path.insert(0, EX); import importlib; b4 = importlib.import_module("accept-b4")
LOG = os.path.join(out, "launch.log"); b4.LOG = LOG
def key(name):
    r = sh("xcrun", "simctl", "spawn", udid, "defaults", "read", "com.apple.Accessibility", name, check=False)
    return r.stdout.strip() if r.returncode == 0 else "absent"
def state(step):
    say(f"{step:<34} ApplicationAccessibilityEnabled={key('ApplicationAccessibilityEnabled'):<7} AccessibilityEnabled={key('AccessibilityEnabled')}")
def launch(tag, install):
    sh("xcrun", "simctl", "terminate", udid, "com.example.composehr", check=False); time.sleep(1)
    open(LOG, "w").close(); stop = os.path.join(out, "stop")
    if os.path.exists(stop): os.remove(stop)
    tl = os.path.join(out, f"{tag}.tl")
    w = subprocess.Popen([sys.executable, os.path.join(HERE, "watch-log.py"), LOG, tl, stop])
    env = dict(os.environ, LAUNCH_LOG=LOG, KONAN_HOT_RELOAD_PORT="5591", SKIP_INSTALL="0" if install else "1")
    rp = subprocess.Popen(["bash", "run.sh"], cwd=EX, env=env, stdout=open(os.path.join(out, f"{tag}-run.out"), "w"), stderr=subprocess.STDOUT)
    ok = b4.wait_log(LOG, r"RENDER_APP", timeout=90); time.sleep(1.5)
    open(stop, "w").close(); w.wait()
    t = {}
    for l in open(tl, errors="replace"):
        p = l.rstrip("\n").split("\t", 1)
        if len(p) == 2:
            for k, pat in (("main", r"^APP MAIN"), ("fin", r"^APP DID FINISH")):
                if k not in t and re.search(pat, p[1].strip()): t[k] = float(p[0])
    ms = round((t["fin"] - t["main"]) * 1000) if len(t) == 2 else None
    say(f"{tag}: first frame={'yes' if ok else 'NO'}  APP MAIN -> APP DID FINISH = {ms} ms")
    return rp
try:
    say(f"device {udid} ({DT.rsplit('.',1)[1]}, {RT.rsplit('.',1)[1]}), axe {sh(b4.AXE, '--version', check=False).stdout.strip()}")
    sh("xcrun", "simctl", "boot", udid); sh("xcrun", "simctl", "bootstatus", udid, "-b")
    state("fresh device")
    if os.environ.get("AX_PRESET_SHARED") == "1":
        for k in ("ApplicationAccessibilityEnabled", "AccessibilityEnabled"):
            sh("xcrun", "simctl", "spawn", udid, "defaults", "write", "com.apple.Accessibility", k, "-bool", "YES")
        state("preset like the shared simulators")
    if os.environ.get("AX_NO_WRITE") != "1":
        sh("xcrun", "simctl", "spawn", udid, "defaults", "write", "com.apple.Accessibility", "ApplicationAccessibilityEnabled", "-bool", "NO")
        if os.environ.get("AX_WRITE_BOTH") == "1":
            sh("xcrun", "simctl", "spawn", udid, "defaults", "write", "com.apple.Accessibility", "AccessibilityEnabled", "-bool", "NO")
        state("after write 0")
        if os.environ.get("AX_REBOOT") == "1":
            sh("xcrun", "simctl", "shutdown", udid); sh("xcrun", "simctl", "boot", udid); sh("xcrun", "simctl", "bootstatus", udid, "-b")
            state("after reboot")
    rp = launch("launch1", install=True); state("after launch 1")
    tap_only = os.environ.get("AX_TAP_ONLY") == "1"
    if not tap_only:
        tree = b4.ui_tree(); labels = [n.get("AXLabel") for n in b4._walk(tree) if n.get("type") == "Button"]
        say(f"axe describe-ui: ok, buttons={labels}"); state("after describe-ui")
    screen = b4.screen_points()
    fixed = {"+": (247.5, 499.4), "Next": (201, 569.7), "Back": (201.2, 528.3)}  # measured by describe-ui in earlier runs
    for label, expect in (("+", r"count=1 page=0"), ("Next", r"count=1 page=1"), ("Back", r"count=1 page=0")):
        mark = len(b4.read(LOG))
        if tap_only:
            x, y = fixed[label]; sh(b4.AXE, "tap", "-x", str(x), "-y", str(y), "--udid", udid)
        else:
            x, y = b4.tap_label(label, screen)
        hit = b4.wait_log(LOG, r"RENDER_APP .*" + expect, since=mark, timeout=10)
        say(f"axe tap {label!r} at ({x:g},{y:g}) -> {'PASS' if hit else 'FAIL'} ({expect})"); state(f"after tap {label}")
    rp.terminate()
    rp = launch("launch2", install=False); state("after relaunch (launch 2)")
    rp.terminate()
    time.sleep(5); state("5 s later")
finally:
    sh("xcrun", "simctl", "terminate", udid, "com.example.composehr", check=False)
    sh("xcrun", "simctl", "shutdown", udid, check=False); sh("xcrun", "simctl", "delete", udid, check=False)
    say(f"deleted {udid}: {'yes' if udid not in sh('xcrun','simctl','list','devices').stdout else 'NO'}")
