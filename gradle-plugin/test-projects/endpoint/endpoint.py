#!/usr/bin/env python3
"""End-point acceptance for a user's project driven only through the plugin's Gradle tasks.

Every check reads the simulator screen (AXe accessibility labels), not app internals:
  ui N     edit a text in the ui module, `gradlew hotReload`, wait until the new text is on screen; PID unchanged
  app N    the same for a text in the app module
  both N   both edits in one hotReload; both texts on screen; PID unchanged
  warm     add a class (hot), then add a field to it -> classifier WARM -> warm restart (new PID, WARM OK),
           then real taps: "+" raises the counter on screen, the page button switches pages and back
  cold N   `gradlew hotReloadRun` N times: first attempt, launch.log has the loader's EH check with
           0 lsda/pc out of range and 0 unresolved personality ("EH OK")

Usage: endpoint.py --project DIR --udid UDID --bundle ID --exe NAME
         --ui-file F --ui-re REGEX --ui-fmt FMT --app-file F --app-re REGEX --app-fmt FMT
         [--plus "+"] [--page LABEL ...] [--ui N --app N --both N --cold N] [-P key=value ...]
REGEX matches a literal piece of text inside a string literal; it is replaced by FMT with {tag} filled in,
and that text must then appear in an accessibility label.
Sources are restored at the end. Stops nothing but this project's app and daemons.
"""
import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time

ap = argparse.ArgumentParser()
ap.add_argument("--project", required=True)
ap.add_argument("--udid", required=True)
ap.add_argument("--bundle", required=True)
ap.add_argument("--exe", required=True)
ap.add_argument("--ui-file", required=True)
ap.add_argument("--ui-re", required=True)
ap.add_argument("--ui-fmt", required=True)
ap.add_argument("--app-file", required=True)
ap.add_argument("--app-re", required=True)
ap.add_argument("--app-fmt", required=True)
ap.add_argument("--plus", default="+")
ap.add_argument("--counter-re", default=r"Counter[^:]*: (\d+)")
ap.add_argument("--page", nargs=2, metavar=("FORWARD", "BACK"), help="labels of the page buttons")
ap.add_argument("--ui", type=int, default=20)
ap.add_argument("--app", type=int, default=20)
ap.add_argument("--both", type=int, default=3)
ap.add_argument("--warm", type=int, default=1)
ap.add_argument("--cold", type=int, default=3)
ap.add_argument("--package", action="store_true", help="hotReloadPackage + hotReloadRun first")
ap.add_argument("-P", dest="props", action="append", default=[])
a = ap.parse_args()

P = os.path.abspath(a.project)
LOG = os.path.join(P, "launch.log")
GRADLE = [os.path.join(P, "gradlew"), "-p", P, "--console=plain"] + [f"-P{p}" for p in a.props]
AXE = os.environ.get("AXE", "axe")
H_DIR = os.path.expanduser("~")


def out(*x):
    print(*x, flush=True)


def clean(s):
    return s.replace(H_DIR, "<HOME>")


def gradle(*args, timeout=900):
    return subprocess.run(GRADLE + list(args), capture_output=True, text=True, timeout=timeout)


def pid():
    o = subprocess.run(["pgrep", "-f", f"Devices/{a.udid}/.*/{a.exe}.app/{a.exe}"], capture_output=True, text=True).stdout.split()
    return o[0] if o else None


def walk(nodes):
    for n in nodes:
        yield n
        yield from walk(n.get("children", []))


def tree():
    r = subprocess.run([AXE, "describe-ui", "--udid", a.udid], capture_output=True, text=True)
    if r.returncode != 0:
        return []
    d = json.loads(r.stdout)
    return d if isinstance(d, list) else [d]


def labels():
    return [n.get("AXLabel") or "" for n in walk(tree())]


def wait_label(pred, timeout=30.0):
    t0 = time.time()
    while time.time() - t0 < timeout:
        for l in labels():
            if pred(l):
                return l
        time.sleep(0.15)
    return None


def screen_points():
    import plistlib
    devs = json.loads(subprocess.run(["xcrun", "simctl", "list", "devices", "-j"], capture_output=True, text=True).stdout)
    dev = [d for v in devs["devices"].values() for d in v if d["udid"] == a.udid][0]
    types = json.loads(subprocess.run(["xcrun", "simctl", "list", "devicetypes", "-j"], capture_output=True, text=True).stdout)
    dt = [t for t in types["devicetypes"] if t["identifier"] == dev["deviceTypeIdentifier"]][0]
    caps = plistlib.load(open(os.path.join(dt["bundlePath"], "Contents/Resources/capabilities.plist"), "rb"))

    def find(o, k):
        if isinstance(o, dict):
            if k in o:
                return o[k]
            for v in o.values():
                r = find(v, k)
                if r is not None:
                    return r
        elif isinstance(o, list):
            for v in o:
                r = find(v, k)
                if r is not None:
                    return r
        return None

    scale = find(caps, "main-screen-scale") or 3
    return find(caps, "main-screen-width") / scale, find(caps, "main-screen-height") / scale


def app_frame():
    t = tree()
    app = next((n for n in walk(t) if n.get("type") == "Application"), None)
    return app["frame"] if app else None


def tap(label):
    t = tree()
    app = next(n for n in walk(t) if n.get("type") == "Application")
    af = app["frame"]
    btn = [n for n in walk(t) if n.get("type") == "Button" and n.get("AXLabel") == label]
    if not btn:
        raise RuntimeError(f"no button {label!r}")
    f = btn[0]["frame"]
    sw, sh = screen_points()
    s = min(sw / af["width"], sh / af["height"])
    ox, oy = (sw - af["width"] * s) / 2, (sh - af["height"] * s) / 2
    x, y = round(ox + (f["x"] + f["width"] / 2) * s, 1), round(oy + (f["y"] + f["height"] / 2) * s, 1)
    subprocess.run([AXE, "tap", "-x", str(x), "-y", str(y), "--udid", a.udid], capture_output=True, text=True, check=True)


def counter():
    for l in labels():
        m = re.search(a.counter_re, l)
        if m:
            return int(m.group(1))
    return None


ORIG = {f: open(os.path.join(P, f)).read() for f in (a.ui_file, a.app_file)}


def edit(rel, regex, new):
    path = os.path.join(P, rel)
    s = open(path).read()
    s2, n = re.subn(regex, new, s, count=1)
    if n != 1:
        raise RuntimeError(f"pattern {regex!r} not found in {rel}")
    open(path, "w").write(s2)


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(round(q * (len(v) - 1))))]


results = {}


def hot_round(kind, i):
    tag = f"{kind}{i}-{int(time.time()) % 100000}"
    texts = []
    if kind in ("ui", "both"):
        new = a.ui_fmt.format(tag=tag)
        edit(a.ui_file, a.ui_re, new)
        texts.append(new)
    if kind in ("app", "both"):
        new = a.app_fmt.format(tag=tag)
        edit(a.app_file, a.app_re, new)
        texts.append(new)
    shown = texts   # the replacement is plain text inside a string literal; it must show up in a label
    p0 = pid()
    t0 = time.time()
    r = gradle("hotReload")
    o = r.stdout + r.stderr
    hot = "compat: HOT" in o and "sent " in o
    loaded = "app loaded the patch" in o
    seen = all(wait_label(lambda l, s=s: s in l, timeout=30) for s in shown)
    ms = (time.time() - t0) * 1000
    p1 = pid()
    ok = r.returncode == 0 and hot and loaded and seen and p0 == p1
    results.setdefault(kind, []).append((ok, ms))
    out(f"{kind}[{i}] {'OK' if ok else 'FAIL'} {ms:.0f}ms hot={hot} loaded={loaded} on_screen={seen} pid {p0}->{p1} text={shown}"
        + ("" if ok else " | " + clean(o[-600:]).replace("\n", " | ")))


def warm_round():
    path = os.path.join(P, a.ui_file)
    open(path, "a").write("\nclass EndpointCfg(val a: Int = 1)\n")
    r = gradle("hotReload")
    added = r.returncode == 0 and "compat: HOT" in r.stdout
    c0 = counter()
    s = open(path).read().replace("class EndpointCfg(val a: Int = 1)", "class EndpointCfg(val a: Int = 1, val b: Int = 2)")
    open(path, "w").write(s)
    p0 = pid()
    t0 = time.time()
    r = gradle("hotReload")
    o = r.stdout + r.stderr
    p1 = pid()
    warm = "compat: WARM" in o and "WARM field-layout" in o and "WARM OK" in o
    wl = [l for l in o.splitlines() if l.startswith("WARM OK")]
    ok = r.returncode == 0 and added and warm and p1 and p1 != p0
    out(f"warm[0] {'OK' if ok else 'FAIL'} {(time.time()-t0)*1000:.0f}ms add_class_hot={added} warm={warm} pid {p0}->{p1} "
        f"counter_before={c0} {wl[0] if wl else ''}" + ("" if ok else " | " + clean(o[-800:]).replace("\n", " | ")))
    # real taps after the warm restart
    time.sleep(2)
    taps = []
    c1 = counter()
    tap(a.plus)
    c2 = None
    t1 = time.time()
    while time.time() - t1 < 10:
        c2 = counter()
        if c1 is not None and c2 == c1 + 1:
            break
        time.sleep(0.2)
    taps.append(f"+:{c1}->{c2}")
    tap_ok = c1 is not None and c2 == c1 + 1
    if a.page:
        fwd, back = a.page
        tap(fwd)
        on2 = wait_label(lambda l: l == back, timeout=10) is not None
        tap(back)
        on1 = wait_label(lambda l: l == fwd, timeout=10) is not None
        taps.append(f"{fwd}->{on2} {back}->{on1}")
        tap_ok = tap_ok and on2 and on1
    crash = re.search(r"Uncaught|EXC_|Terminating app|HOOK class=", open(LOG, errors="replace").read())
    p2 = pid()
    tap_ok = tap_ok and not crash and p2 == p1
    out(f"taps-after-warm {'OK' if tap_ok else 'FAIL'} {' '.join(taps)} pid {p1}->{p2} crash={'none' if not crash else crash.group(0)}")
    results["warm"] = [(ok, 0)]
    results["taps"] = [(tap_ok, 0)]


EH_RE = re.compile(r"EH check: (\d+) FDE, (\d+) lsda, (\d+) lsda-out-of-range, (\d+) pc-out-of-range, (\d+) unresolved-personality")


def cold_round(i):
    r = gradle("hotReloadRun")
    o = r.stdout + r.stderr
    m = re.search(r"app started \(attempt (\d+), pid (\d+), ([\d.]+) s", o)
    log = open(LOG, errors="replace").read()
    eh = EH_RE.search(log)
    eh_ok = bool(eh) and eh.group(3) == eh.group(4) == eh.group(5) == "0" and int(eh.group(1)) > 0
    ok = r.returncode == 0 and m and m.group(1) == "1" and eh_ok
    out(f"cold[{i}] {'OK' if ok else 'FAIL'} attempt={m.group(1) if m else '-'} pid={m.group(2) if m else '-'} "
        f"{m.group(3) if m else '-'}s EH={'%s FDE/%s lsda/%s+%s out/%s unresolved' % eh.groups() if eh else 'missing'} eh_ok={eh_ok}")
    results.setdefault("cold", []).append((ok, 0))


try:
    if a.package:
        r = gradle("hotReloadPackage", timeout=1800)
        out("package", "OK" if r.returncode == 0 else "FAIL", clean("".join(l + "\n" for l in r.stdout.splitlines()
                                                                         if re.search(r"toolchain OK|bundle check|compat: baseline|BUILD", l))).strip())
        r = gradle("hotReloadRun")
        out("run", clean(" ".join(l for l in r.stdout.splitlines() if "app started" in l or "BUILD" in l)))
    sw, sh = screen_points()
    f = app_frame()
    out(f"screen {sw:g}x{sh:g} points, app frame {f['width']:g}x{f['height']:g}" if f else "app frame: none")
    out("pid0", pid())
    for i in range(a.ui):
        hot_round("ui", i)
    for i in range(a.app):
        hot_round("app", i)
    for i in range(a.both):
        hot_round("both", i)
    for i in range(a.warm):
        warm_round()
    for i in range(a.cold):
        cold_round(i)
finally:
    for f, s in ORIG.items():
        open(os.path.join(P, f), "w").write(s)
    out("# sources restored")
    # leave the reload port free for the next project on this simulator
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, a.bundle], capture_output=True)
    out("# app stopped")

out("=== summary ===")
failed = 0
for k in ("ui", "app", "both", "warm", "taps", "cold"):
    v = results.get(k)
    if not v:
        continue
    n_ok = sum(1 for ok, _ in v if ok)
    failed += len(v) - n_ok
    ms = [m for ok, m in v if ok and m]
    extra = f" p50={pct(ms, .5):.0f}ms p90={pct(ms, .9):.0f}ms" if ms else ""
    out(f"{k}: ok={n_ok}/{len(v)}{extra}")
sys.exit(1 if failed else 0)
