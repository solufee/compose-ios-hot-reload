#!/usr/bin/env python3
"""B4 regression: change type -> expected result, screenshots compared.

Runs one command over the whole matrix:

  source env.sh (KOTLIN_DIST etc.); python3 accept-b4.py

For each edit it runs `hotreload.sh ui-klib` then `hotreload.sh reload`
(the classifier decides), checks the exit code, the PID, the RENDER line,
and compares `simctl io screenshot` captures:

  hot  change: process/PID must not change, a new RENDER page=2 count=3
               must appear, and the screenshot must differ.
  warm change: the classifier must print WARM reasons, the app must restart
               (new PID), `WARM OK` must be printed, the restored page and
               counter must be 2/3, and the screenshot must match the
               pre-edit screenshot.

The app is started at page 2 / count 3 with --hr-state, then state is written
by the app itself, so warm restarts restore it.

After the last warm restart, real taps (AXe, screen points) check that the
restored app still works: Back, + (counter 3 -> 4), Next, Back, with the same
PID and no crash or uncaught exception in the log.
"""
import hashlib
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
UI = os.path.join(HERE, "ui/src/main/kotlin/ui.kt")
APP = os.path.join(HERE, "app/src/main/kotlin/main.kt")
HOTRELOAD = os.path.join(HERE, "hotreload.sh")
RUN = os.path.join(HERE, "run.sh")
LOG = os.path.join(HERE, "launch.log")
SHOTS = os.path.join(HERE, "build/regression/shots")
UDID = os.environ.get("UDID", "0B879832-98D9-49A5-AC00-8BAB579AFBB2")
BUNDLE = "com.example.composehr"
PORT = os.environ.get("KONAN_HOT_RELOAD_PORT", "5567")
ENV = dict(os.environ)
ENV.update({
    "UDID": UDID,
    "KONAN_HOT_RELOAD_PORT": PORT,
    "JAVA_HOME": os.environ.get("JAVA_HOME", os.path.expanduser(
        "~/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.21+9/Contents/Home")),
})
PROC = f"Devices/{UDID}/.*ComposeHR.app/ComposeHR"


def sh(cmd, timeout=600):
    return subprocess.run(cmd, cwd=HERE, env=ENV, capture_output=True,
                          text=True, timeout=timeout)


def pid():
    out = subprocess.run(["pgrep", "-f", PROC], capture_output=True, text=True).stdout.split()
    return out[0] if out else None


def read(path):
    try:
        with open(path, "rb") as f:
            return f.read().replace(b"\r", b"\n").decode("utf-8", "replace")
    except FileNotFoundError:
        return ""


def wait_log(path, pattern, since=0, timeout=120):
    t0 = time.time()
    while time.time() - t0 < timeout:
        data = read(path)
        if len(data) < since:
            since = 0  # the launcher truncates its log on every start
        if len(data) > since and re.search(pattern, data[since:]):
            return True
        time.sleep(0.1)
    return False


def edit(path, old, new):
    s = open(path).read()
    assert old in s, f"edit anchor missing in {path}: {old[:60]}"
    open(path, "w").write(s.replace(old, new, 1))


def shot(name):
    os.makedirs(SHOTS, exist_ok=True)
    png = os.path.join(SHOTS, name + ".png")
    r = subprocess.run(["xcrun", "simctl", "io", UDID, "screenshot", png],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"screenshot failed: {r.stderr.strip()}")
    dims = subprocess.run(["sips", "-g", "pixelWidth", "-g", "pixelHeight", png],
                          capture_output=True, text=True).stdout
    w = int(re.search(r"pixelWidth:\s*(\d+)", dims).group(1))
    h = int(re.search(r"pixelHeight:\s*(\d+)", dims).group(1))
    cw, ch = int(w * 0.8), int(h * 0.6)
    cx, cy = (w - cw) // 2, int(h * 0.2)
    cropped = os.path.join(SHOTS, name + "-crop.png")
    subprocess.run(["sips", "-c", str(ch), str(cw), "--cropOffset", str(cy), str(cx),
                    png, "--out", cropped], capture_output=True)
    bmp = os.path.join(SHOTS, name + ".bmp")
    subprocess.run(["sips", "-s", "format", "bmp", cropped, "--out", bmp],
                   capture_output=True)
    return hashlib.sha256(open(bmp, "rb").read()).hexdigest()


def start_fresh():
    subprocess.run(["pkill", "-f", os.path.join(HERE, "run.sh")],
                   capture_output=True)
    subprocess.run(["xcrun", "simctl", "terminate", UDID, BUNDLE],
                   capture_output=True)
    time.sleep(1)
    before = len(read(LOG))
    env = dict(ENV)
    env["APP_ARGS"] = "--hr-state=2,3"
    subprocess.Popen(["bash", RUN], cwd=HERE, env=env,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True)
    if not wait_log(LOG, r"count=3 page=2", since=before):
        raise RuntimeError("app did not start at page=2 count=3")
    time.sleep(2.5)  # let the first frame composite before screenshotting
    return pid()


def reload_once(warm_expected):
    """Run ui-klib + reload. Returns (ok, out, before_log, pid_after)."""
    r = sh(["bash", HOTRELOAD, "ui-klib"])
    if r.returncode != 0:
        return False, f"ui-klib failed: {r.stderr[-400:]}", None, None
    p0 = pid()
    before_launch = len(read(LOG))
    before_warm = before_launch
    t0 = time.time()
    r = sh(["bash", HOTRELOAD, "reload"], timeout=600)
    out = r.stdout + r.stderr
    if r.returncode != 0:
        return False, out, None, pid()
    return True, out, (before_launch, before_warm, t0), pid()


def warm_lines(out):
    return " ; ".join(l for l in out.splitlines() if l.startswith("WARM "))


def check_hot(case, expected_shot_change, out, marks, p_before):
    before_launch, before_warm, t0 = marks
    ok = "compat: warm restart required" not in out
    ok = ok and wait_log(LOG, r"RENDER_APP .*count=3 page=2", since=before_launch, timeout=30)
    time.sleep(1.0)  # let the patched frame composite before screenshotting
    p_after = pid()
    s = shot(case)
    if expected_shot_change is None:
        changed, detail_shot = True, "shot_not_checked"
    else:
        changed = s != expected_shot_change
        detail_shot = f"shot_changed={changed}"
    return ok and p_after == p_before and changed, \
        f"pid {p_before}->{p_after} render={ok} {detail_shot} {warm_lines(out)}", s, p_after


def check_warm(case, expected_shot, out, marks, p_before):
    before_launch, before_warm, t0 = marks
    has_reason = re.search(r"^WARM \S+", out, re.M) is not None
    has_ok = "WARM OK" in out
    p_after = pid()
    ok = has_reason and has_ok and p_after != p_before
    ok = ok and wait_log(LOG, r"RENDER_APP .*count=3 page=2", since=before_warm, timeout=30)
    time.sleep(2.5)  # warm restart: wait for the first frame to composite
    s = shot(case)
    same = s == expected_shot
    return ok and same, \
        f"reason={has_reason} WARM_OK={has_ok} pid {p_before}->{p_after} shot_same={same}", s, p_after


# ---- real taps (AXe) ---------------------------------------------------------
# AXe reports element frames in app points; the app may run in a letterboxed
# compatibility window (320x480 on a 402x874 screen), while `axe tap` takes
# screen points. So buttons are located by label in `axe describe-ui` and the
# frame centre is mapped to screen points (REPORT 9.11.1). Tapping by label
# reports success but misses the button.
AXE = os.environ.get("AXE", "axe")
CRASH_RE = r"ModifierNodeDetachedCancellationException|Uncaught|HOOK class=|EXC_|Terminating app"


def _find_key(obj, key):
    if isinstance(obj, dict):
        if key in obj:
            return obj[key]
        for v in obj.values():
            r = _find_key(v, key)
            if r is not None:
                return r
    elif isinstance(obj, list):
        for v in obj:
            r = _find_key(v, key)
            if r is not None:
                return r
    return None


def screen_points():
    """(width, height) of the simulator screen in points."""
    import json
    import plistlib
    devs = json.loads(subprocess.run(["xcrun", "simctl", "list", "devices", "-j"],
                                     capture_output=True, text=True).stdout)
    dev = [d for v in devs["devices"].values() for d in v if d["udid"] == UDID][0]
    types = json.loads(subprocess.run(["xcrun", "simctl", "list", "devicetypes", "-j"],
                                      capture_output=True, text=True).stdout)
    dt = [t for t in types["devicetypes"] if t["identifier"] == dev["deviceTypeIdentifier"]][0]
    caps = plistlib.load(open(os.path.join(dt["bundlePath"],
                                           "Contents/Resources/capabilities.plist"), "rb"))
    w, h = _find_key(caps, "main-screen-width"), _find_key(caps, "main-screen-height")
    scale = _find_key(caps, "main-screen-scale") or 3
    return w / scale, h / scale


def ui_tree():
    import json
    r = subprocess.run([AXE, "describe-ui", "--udid", UDID], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"axe describe-ui failed: {r.stderr.strip()[:300]}")
    data = json.loads(r.stdout)
    return data if isinstance(data, list) else [data]


def _walk(nodes):
    for n in nodes:
        yield n
        yield from _walk(n.get("children", []))


def tap_label(label, screen):
    """Tap the button labelled `label`; returns the screen point used."""
    tree = ui_tree()
    app = next(n for n in _walk(tree) if n.get("type") == "Application")
    af = app["frame"]
    btn = [n for n in _walk(tree) if n.get("type") == "Button" and n.get("AXLabel") == label]
    if not btn:
        raise RuntimeError(f"no button labelled {label!r} on screen")
    f = btn[0]["frame"]
    sw, sh_ = screen
    s = min(sw / af["width"], sh_ / af["height"])
    ox, oy = (sw - af["width"] * s) / 2, (sh_ - af["height"] * s) / 2
    x = round(ox + (f["x"] + f["width"] / 2) * s, 1)
    y = round(oy + (f["y"] + f["height"] / 2) * s, 1)
    r = subprocess.run([AXE, "tap", "-x", str(x), "-y", str(y), "--udid", UDID],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"axe tap {label} failed: {r.stderr.strip()[:300]}")
    return x, y


AX_HELPER = os.path.join(HERE, "tools", "sim-ax-keys.sh")


def ax_keys(cmd):
    """Run tools/sim-ax-keys.sh save|restore for this simulator (Issue #60): `axe describe-ui` turns the accessibility
    switch on, which makes every app start ~1.8 s slower; restore writes the saved values back. Returns (ok, line)."""
    r = subprocess.run(["bash", AX_HELPER, cmd, UDID], capture_output=True, text=True)
    line = (r.stdout.strip() or r.stderr.strip()).splitlines()[-1:] or [f"sim-ax-keys.sh {cmd}: no output"]
    return r.returncode == 0, line[0]


def tap_round_trip(p_before):
    """After a warm restart (page 2, count 3): Back, +, Next, Back.

    Each tap must produce the expected RENDER_APP state, the PID must stay the
    same, and the log must not show a crash/uncaught exception.
    """
    screen = screen_points()
    start = len(read(LOG))
    steps = [("Back", r"count=3 page=0"), ("+", r"count=4 page=0"),
             ("Next", r"count=4 page=1"), ("Back", r"count=4 page=0")]
    done = []
    for label, expect in steps:
        mark = len(read(LOG))
        x, y = tap_label(label, screen)
        if not wait_log(LOG, r"RENDER_APP .*" + expect, since=mark, timeout=10):
            return False, f"tap {label}@({x},{y}) no '{expect}' after {done}"
        done.append(f"{label}@({x:g},{y:g})->{expect.replace(' ', '/')}")
        time.sleep(0.5)
        if label == "Next":
            kept_p2 = any(n.get("AXLabel") == "Counter kept: 4" for n in _walk(ui_tree()))
    kept = any(n.get("AXLabel") == "Counter: 4" for n in _walk(ui_tree()))
    crash = re.search(CRASH_RE, read(LOG)[start:])
    p_after = pid()
    ok = kept and kept_p2 and not crash and p_after == p_before
    return ok, (f"pid {p_before}->{p_after} {' '.join(done)} page2_kept4={kept_p2} "
                f"page1_counter4={kept} crash={'none' if not crash else crash.group(0)}")


def main():
    p0 = start_fresh()
    print("started app pid", p0, flush=True)
    base = shot("00-base")
    results = []

    def hot(name, fn, change_shot=True):
        nonlocal p0
        fn()
        ok, out, marks, p_after = reload_once(False)
        if not ok:
            results.append((name, "hot", "FAIL", out[-400:].replace("\n", " | ")))
            return
        expect = base if change_shot else None
        good, detail, s, p0 = check_hot(name, expect, out, marks, p0)
        results.append((name, "hot", "PASS" if good else "FAIL", detail))

    def warm(name, fn):
        nonlocal p0
        s_before = shot(name + "-before")
        fn()
        ok, out, marks, p_after = reload_once(True)
        if not ok:
            results.append((name, "warm", "FAIL", out[-500:].replace("\n", " | ")))
            return
        good, detail, s, p0 = check_warm(name, s_before, out, marks, p0)
        results.append((name, "warm", "PASS" if good else "FAIL", detail))

    # ---- hot cases ---------------------------------------------------------
    hot("01-text", lambda: edit(UI, 'const val UI_MARK = "ui-mf2"', 'const val UI_MARK = "ui-text"'))
    hot("02-color", lambda: edit(UI, "Color(0xFF7FD1AE)", "Color(0xFFE07A5F)"))
    hot("03-if", lambda: edit(
        UI,
        '    Text("UI $UI_MARK", color = Color(0xFFE07A5F), fontSize = 18.sp)',
        '    if (tick % 2 == 0) Text("even $UI_MARK", color = Color(0xFFE07A5F), fontSize = 18.sp) '
        'else Text("odd $UI_MARK", color = Color(0xFFE07A5F), fontSize = 18.sp)'))
    # Revert the parity branch: later screenshot comparisons need a stable frame.
    hot("03b-revert-if", lambda: edit(
        UI,
        '    if (tick % 2 == 0) Text("even $UI_MARK", color = Color(0xFFE07A5F), fontSize = 18.sp) '
        'else Text("odd $UI_MARK", color = Color(0xFFE07A5F), fontSize = 18.sp)',
        '    Text("UI $UI_MARK", color = Color(0xFFE07A5F), fontSize = 18.sp)'))
    hot("04-fnbody", lambda: (
        edit(UI, 'const val UI_MARK = "ui-text"', 'const val UI_MARK = "ui-text"\n\n'
             'private fun label(): String = "bodyA"'),
        edit(UI, 'Text("UI $UI_MARK", color = Color(0xFFE07A5F), fontSize = 18.sp)',
             'Text("UI $UI_MARK ${label()}", color = Color(0xFFE07A5F), fontSize = 18.sp)')))
    hot("05-add-composable", lambda: (
        edit(UI, '    println("RENDER_UI $UI_MARK tick=$tick")',
             '    println("RENDER_UI $UI_MARK tick=$tick")\n    ExtraMark()'),
        open(UI, "a").write('\n@Composable\nfun ExtraMark() { Text("extra", color = Color.White) }\n')))
    hot("06-remove-composable", lambda: (
        edit(UI, '    ExtraMark()\n', ''),
        edit(UI, '\n@Composable\nfun ExtraMark() { Text("extra", color = Color.White) }\n', '')))

    # ---- warm cases --------------------------------------------------------
    def add_class():
        open(UI, "a").write('\nclass UiConfig(val a: Int = 1)\n')

    hot("07a-add-class", add_class, change_shot=False)
    warm("07b-field-layout", lambda: edit(
        UI, "class UiConfig(val a: Int = 1)", "class UiConfig(val a: Int = 1, val b: Int = 2)"))

    hot("08a-add-fun", lambda: open(UI, "a").write('\nfun fmt(x: Int): String = "fmt$x"\n'),
        change_shot=False)
    # #28: a signature change of a top-level or final function is HOT (its callers are recompiled with it).
    hot("08b-signature", lambda: edit(
        UI, 'fun fmt(x: Int): String = "fmt$x"', 'fun fmt(x: Long): String = "fmt$x"'), change_shot=False)

    hot("09a-add-inline", lambda: open(UI, "a").write('\ninline fun twice2(x: Int): Int = x * 2\n'),
        change_shot=False)
    warm("09b-inline-body", lambda: edit(
        UI, "inline fun twice2(x: Int): Int = x * 2", "inline fun twice2(x: Int): Int = x * 3"))

    hot("10a-add-suspend", lambda: open(UI, "a").write('\nsuspend fun later(x: Int): Int = x\n'),
        change_shot=False)
    warm("10b-suspend-body", lambda: edit(
        UI, "suspend fun later(x: Int): Int = x", "suspend fun later(x: Int): Int = x + 1"))

    warm("11-top-level-property", lambda: open(UI, "a").write('\nval extraProp: Int = 1\n'))

    # ---- real taps after the warm restart (fixed regression item) ----------
    _, ax_saved = ax_keys("save")  # before the first describe-ui; written back whatever happens in the tap step
    print(ax_saved, flush=True)
    try:
        good, detail = tap_round_trip(p0)
    except Exception as e:  # noqa: BLE001 - report any tap failure as FAIL
        good, detail = False, f"error: {e}"
    finally:
        ax_ok, ax_line = ax_keys("restore")
        print(ax_line, flush=True)
    results.append(("12-tap-after-warm", "tap", "PASS" if good else "FAIL", detail))
    results.append(("13-ax-restored", "ax", "PASS" if ax_ok else "FAIL", ax_line))
    shot("12-tap-after-warm")

    # ---- summary -----------------------------------------------------------
    print("\n=== B4 matrix ===")
    failed = 0
    for name, kind, verdict, detail in results:
        if verdict != "PASS":
            failed += 1
        print(f"{name:26} {kind:5} {verdict:4} {detail}")
    print(f"summary: {len(results) - failed}/{len(results)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
