#!/usr/bin/env python3
"""Stress test for composable lambdas added to / removed from an existing composable (ComposableSingletons).

Each lambda becomes a field of the file's synthetic `ComposableSingletons$<File>Kt` object, which the running
app allocated long ago. This test hot-reloads, in one process:
  rounds x (add a Button lambda -> remove it -> add it back), then `extra` more add/remove reloads,
with a real tap on the app's "+" button after every reload. Every reload must be HOT, be loaded, show the
expected label (or its absence), keep the PID, and the counter must follow the taps; the launch log must
show no crash or uncaught exception.

Usage: lambda_stress.py --project DIR --file REL --after-line 'Text("UI $UI_MARK"' --udid U --exe NAME
       [--rounds 5] [--extra 5] [--plus +] [--counter-re 'Counter: (\\d+)']
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

ap = argparse.ArgumentParser()
ap.add_argument("--project", required=True)
ap.add_argument("--file", required=True)
ap.add_argument("--after-line", required=True, help="start of the line after which the Button lambda is inserted")
ap.add_argument("--udid", required=True)
ap.add_argument("--exe", required=True)
ap.add_argument("--rounds", type=int, default=5)
ap.add_argument("--extra", type=int, default=5)
ap.add_argument("--plus", default="+")
ap.add_argument("--counter-re", default=r"Counter: (\d+)")
ap.add_argument("--gradlew")
a = ap.parse_args()
P = os.path.abspath(a.project)
F = os.path.join(P, a.file)
LOG = os.path.join(P, "launch.log")
GRADLEW = a.gradlew or os.path.join(P, "gradlew")
CRASH = re.compile(r"Uncaught|EXC_|Terminating app|HOOK class=|SIGSEGV|SIGBUS|Segmentation")


def walk(ns):
    for n in ns:
        yield n
        yield from walk(n.get("children", []))


def tree():
    r = subprocess.run(["axe", "describe-ui", "--udid", a.udid], capture_output=True, text=True)
    d = json.loads(r.stdout) if r.returncode == 0 else []
    return d if isinstance(d, list) else [d]


def labels():
    return [n.get("AXLabel") or "" for n in walk(tree())]


def wait(pred, timeout=30):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if pred(labels()):
            return True
        time.sleep(0.2)
    return False


def counter():
    for l in labels():
        m = re.search(a.counter_re, l)
        if m:
            return int(m.group(1))
    return None


def tap(label):
    t = tree()
    app = next(n for n in walk(t) if n.get("type") == "Application")
    af = app["frame"]
    b = [n for n in walk(t) if n.get("type") == "Button" and n.get("AXLabel") == label][0]["frame"]
    # app frame may be letterboxed; map app points to screen points like accept-b4.py does
    sw, sh = 402.0, 874.0
    s = min(sw / af["width"], sh / af["height"])
    ox, oy = (sw - af["width"] * s) / 2, (sh - af["height"] * s) / 2
    x, y = round(ox + (b["x"] + b["width"] / 2) * s, 1), round(oy + (b["y"] + b["height"] / 2) * s, 1)
    subprocess.run(["axe", "tap", "-x", str(x), "-y", str(y), "--udid", a.udid], capture_output=True, check=True)


def pid():
    o = subprocess.run(["pgrep", "-f", f"Devices/{a.udid}/.*/{a.exe}.app/{a.exe}"], capture_output=True, text=True).stdout.split()
    return o[0] if o else None


orig = open(F).read()
lines = orig.splitlines(keepends=True)
idx = next(i for i, l in enumerate(lines) if l.strip().startswith(a.after_line))
indent = re.match(r"\s*", lines[idx]).group(0)


def with_lambda(tag):
    ins = f'{indent}androidx.compose.material3.Button(onClick = {{ }}) {{ androidx.compose.material3.Text("lam-{tag}") }}\n'
    return "".join(lines[:idx + 1] + [ins] + lines[idx + 1:])


plan = []
for r in range(a.rounds):
    plan += [("add", f"r{r}"), ("remove", None), ("add-back", f"r{r}")]
for e in range(a.extra):
    plan += [("add", f"e{e}") if e % 2 == 0 else ("remove", None)]

p0 = pid()
log0 = len(open(LOG, errors="replace").read())
failed = 0
print(f"# lambda stress: {a.file}, pid {p0}, {len(plan)} reloads, a tap on {a.plus!r} after each", flush=True)
# Issue #66: `axe describe-ui` turns on the simulator's accessibility switch (ApplicationAccessibilityEnabled,
# AccessibilityEnabled; ~1.8 s more at every app start). The shared helper saves both keys before the first describe-ui
# and writes them back when the script ends, also after a failure (absent keys are written back as NO).
AX_HELPER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../../example-gradle/tools/sim-ax-keys.sh")


def ax_keys(cmd):
    """Run example-gradle/tools/sim-ax-keys.sh save|restore for this simulator. Returns (ok, line)."""
    r = subprocess.run(["bash", AX_HELPER, cmd, a.udid], capture_output=True, text=True)
    line = (r.stdout.strip() or r.stderr.strip()).splitlines()[-1:] or [f"sim-ax-keys.sh {cmd}: no output"]
    return r.returncode == 0, line[0]


ax_ok = False
print(ax_keys("save")[1], flush=True)
try:
    for i, (op, tag) in enumerate(plan):
        src = with_lambda(tag) if tag else orig
        open(F, "w").write(src)
        t0 = time.time()
        r = subprocess.run([GRADLEW, "-p", P, "hotReload", "--console=plain"], capture_output=True, text=True)
        out = r.stdout + r.stderr
        hot = "compat: HOT" in out
        loaded = "app loaded the patch" in out
        if tag:
            shown = wait(lambda ls, t=tag: any(f"lam-{t}" in l for l in ls))
        else:
            shown = wait(lambda ls: not any(l.startswith("lam-") for l in ls))
        c0 = counter()
        tap(a.plus)
        tapped = wait(lambda ls, c=c0: c is not None and any(re.search(a.counter_re, l) and int(re.search(a.counter_re, l).group(1)) == c + 1 for l in ls), timeout=10)
        p1 = pid()
        crash = CRASH.search(open(LOG, errors="replace").read()[log0:])
        ok = r.returncode == 0 and hot and loaded and shown and tapped and p1 == p0 and not crash
        failed += not ok
        print(f"reload{i:02d} {'OK' if ok else 'FAIL'} {op:8} {tag or '-':4} {(time.time()-t0)*1000:.0f}ms hot={hot} loaded={loaded} "
              f"screen={shown} tap +:{c0}->{'+1' if tapped else '?'} pid {p0}->{p1} crash={'none' if not crash else crash.group(0)}"
              + ("" if r.returncode == 0 else " | " + out[-300:].replace("\n", " | ")), flush=True)
        if p1 != p0:
            break
finally:
    open(F, "w").write(orig)
    print("# file restored", flush=True)
    ax_ok, ax_line = ax_keys("restore")
    print(f"{'PASS' if ax_ok else 'FAIL'} simulator accessibility switch written back (sim-ax-keys.sh restore: read back == value before describe-ui) | {ax_line}", flush=True)
print(f"summary: {len(plan) - failed}/{len(plan)} reloads OK in one process")
sys.exit(1 if failed or not ax_ok else 0)
