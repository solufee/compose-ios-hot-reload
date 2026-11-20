#!/usr/bin/env python3
"""0.2.3 acceptance for #9 (UIDeviceFamily of the generated Info.plist) on an iPad simulator.

On the KMP wizard template (sources unchanged, plus a static Text):
  control    default deviceFamily ([1]): the generated Info.plist has UIDeviceFamily [1]; on the iPad the app
             runs in iPhone compatibility mode (its window is smaller than the screen)
  iPad       cmpHotReload { deviceFamily = listOf(1, 2) }: UIDeviceFamily [1, 2]; the app's window fills the iPad
             screen (accessibility frame and a screenshot); a hot reload there is HOT and shows without taps

Usage: accept-ipad.py --zip WIZARD.zip --work DIR --plugin-repo URL --plugin-version V --toolchain DIR --udid <iPad UDID>
       [--port 5570] [--shots DIR]
"""
import argparse
import json
import os
import plistlib
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
ap.add_argument("--udid", required=True, help="a booted iPad simulator")
ap.add_argument("--port", default="5570")
ap.add_argument("--shots", default=None, help="where to keep the screenshots (default: WORK)")
a = ap.parse_args()

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "../../.."))
WORK = os.path.abspath(a.work)
P = os.path.join(WORK, "KmpHr")
SHOTS = os.path.abspath(a.shots or WORK)
HOME = os.path.expanduser("~")
EXE, BUNDLE = "KmpHr", "org.example.kmphr.hotreload"
APP = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/App.kt")
BUILD = os.path.join(P, "shared/build.gradle.kts")
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
    r = subprocess.run([os.path.join(P, "gradlew"), "-p", P, *args, "--console=plain"], cwd=P, capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout + r.stderr


def tree():
    r = subprocess.run(["axe", "describe-ui", "--udid", a.udid], capture_output=True, text=True)
    try:
        d = json.loads(r.stdout) if r.returncode == 0 else []
    except ValueError:
        d = []
    return d if isinstance(d, list) else [d]


def walk(ns):
    for n in ns:
        yield n
        yield from walk(n.get("children", []))


def app_frame():
    return next((n.get("frame") for n in walk(tree()) if n.get("type") == "Application"), None)


def label_frame(text, timeout=25):
    t0 = time.time()
    while time.time() - t0 < timeout:
        for n in walk(tree()):
            if text in (n.get("AXLabel") or ""):
                return n.get("frame")
        time.sleep(0.3)
    return None


def screen_points():
    """The simulator screen in points (from the device type's screen size and scale)."""
    out = subprocess.run(["xcrun", "simctl", "list", "devices", "-j"], capture_output=True, text=True).stdout
    for devs in json.loads(out)["devices"].values():
        for d in devs:
            if d["udid"] == a.udid:
                info = subprocess.run(["xcrun", "simctl", "list", "devicetypes", "-j"], capture_output=True, text=True).stdout
                for t in json.loads(info)["devicetypes"]:
                    if t["identifier"] == d["deviceTypeIdentifier"]:
                        return t.get("name"), d["deviceTypeIdentifier"]
    return None, None


def plist_family():
    path = os.path.join(P, "shared/build/hotreload/generated/Info.plist")
    return plistlib.load(open(path, "rb")).get("UIDeviceFamily") if os.path.exists(path) else None


def screenshot(name):
    path = os.path.join(SHOTS, name)
    subprocess.run(["xcrun", "simctl", "io", a.udid, "screenshot", path], capture_output=True)
    return path


def stop():
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)


say(f"# #9 acceptance on {screen_points()[0]} ({a.udid}), plugin {a.plugin_version}, {time.strftime('%F %T')}")
if os.path.exists(WORK):
    shutil.rmtree(WORK)
os.makedirs(WORK)
os.makedirs(SHOTS, exist_ok=True)
zipfile.ZipFile(a.zip).extractall(WORK)
os.chmod(os.path.join(P, "gradlew"), 0o755)
edit(os.path.join(P, "settings.gradle.kts"), "pluginManagement {\n    repositories {\n",
     f'pluginManagement {{\n    repositories {{\n        maven {{ url = uri("{a.plugin_repo}") }}\n')
edit(BUILD, "plugins {\n", f'plugins {{\n    id("com.solufee.cmp.hotreload") version "{a.plugin_version}"\n')
open(os.path.join(P, "local.properties"), "w").write(
    f"cmp.hotreload.toolchainDir={a.toolchain}\ncmp.hotreload.simulatorUdid={a.udid}\ncmp.hotreload.port={a.port}\n")
edit(APP, '                Text("Click me!")\n            }\n', '                Text("Click me!")\n            }\n            Text("Static: st-1")\n')
try:
    say("## control: default deviceFamily")
    rc, out = gradle("hotReloadPackage")
    check("package (default)", rc == 0, f"UIDeviceFamily {plist_family()}")
    check("default generated Info.plist: UIDeviceFamily [1]", plist_family() == [1])
    rc, out = gradle("hotReloadRun")
    f1 = label_frame("Static: st-1") and app_frame()
    shot1 = screenshot("ipad-default-family1.png")
    say(f"  app frame {f1}; screenshot {os.path.basename(shot1)}")
    stop()

    say("## deviceFamily = listOf(1, 2)")
    open(BUILD, "a").write("\ncmpHotReload {\n    deviceFamily = listOf(1, 2)\n}\n")
    rc, out = gradle("hotReloadPackage")
    check("package with deviceFamily [1, 2]", rc == 0 and plist_family() == [1, 2], f"UIDeviceFamily {plist_family()}")
    rc, out = gradle("hotReloadRun")
    check("run on the iPad", rc == 0 and "app started" in out)
    label_frame("Static: st-1")
    f2 = app_frame()
    shot2 = screenshot("ipad-family12-fullscreen.png")
    bigger = f1 and f2 and f2["width"] > f1["width"] and f2["height"] > f1["height"]
    check("the app window is iPad size (larger than in iPhone compatibility mode)", bigger and f2["width"] >= 744,
          f"default {f1 and (f1['width'], f1['height'])} -> [1,2] {f2 and (f2['width'], f2['height'])}; screenshot {os.path.basename(shot2)}")
    edit(APP, '"Static: st-1"', '"Static: st-ipad"')
    rc, out = gradle("hotReload")
    seen = label_frame("Static: st-ipad") is not None
    check("hot reload on the iPad: HOT, shows without taps", rc == 0 and "compat: HOT" in out and "app loaded the patch" in out and seen)
    screenshot("ipad-family12-after-hot-reload.png")
except Exception as e:
    check(f"script error: {type(e).__name__}: {e}", False)
finally:
    stop()
    gradle("hotReloadDaemonStop")
failed = [n for n, ok in results if not ok]
say(f"summary: {len(results) - len(failed)}/{len(results)} PASS" + ("" if not failed else f"; FAILED: {failed}"))
sys.exit(1 if failed else 0)
