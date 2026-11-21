#!/usr/bin/env python3
"""#52: warm-restart packaging times of a project through the plugin.

Packages and launches the app, then makes N warm restarts caused by a library-module edit ("ui") and N caused by an
app-module edit ("app"): each adds a new top-level property (always `WARM top-level-property`). Prints every warm
restart's total, packaging and the package breakdown from the plugin's `package timing` line, and the medians.
The edited files are restored at the end; the app (by bundle id) and the project's compile daemons are stopped.

Usage: warm-timing.py --project DIR [--gradlew PATH] --lib-file REL --app-file REL --bundle ID --udid U [--n 3] [--label TEXT]
"""
import argparse
import os
import re
import statistics
import subprocess
import sys
import time

ap = argparse.ArgumentParser()
ap.add_argument("--project", required=True)
ap.add_argument("--gradlew")
ap.add_argument("--lib-file", required=True)
ap.add_argument("--app-file", required=True)
ap.add_argument("--bundle", required=True)
ap.add_argument("--udid", required=True)
ap.add_argument("--n", type=int, default=3)
ap.add_argument("--label", default="")
a = ap.parse_args()
P = os.path.abspath(a.project)
GRADLEW = a.gradlew or os.path.join(P, "gradlew")
HOME = os.path.expanduser("~")
REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../.."))


def clean(t):
    return t.replace(REPO, "<REPO>").replace(HOME, "<HOME>")


def say(line=""):
    print(clean(line), flush=True)


def gradle(*args):
    t0 = time.time()
    r = subprocess.run([GRADLEW, "-p", P, *args, "--console=plain"], cwd=P, capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr, time.time() - t0


TIMING = re.compile(r"package timing ms=(\d+) compile=(\d+) merge=(\d+) embed\+bundle=(\d+)(?: embed=(\d+) bundle=(\d+) baseline-wait=(\d+))?")
WARM_OK = re.compile(r"WARM OK ms=(\d+) ms_package=(\d+) ms_relaunch=(\d+)")

files = {k: os.path.join(P, v) for k, v in (("ui", a.lib_file), ("app", a.app_file))}
orig = {k: open(f).read() for k, f in files.items()}
rows = []
say(f"# warm-restart timing {a.label}, {time.strftime('%F %T')}, project {os.path.basename(P)}, simulator {a.udid}")
try:
    rc, out, dt = gradle("hotReloadPackage")
    say(f"package rc={rc} {dt:.0f}s " + "; ".join(l for l in out.splitlines() if "package timing" in l or "payload list" in l))
    rc2, out2, _ = gradle("hotReloadRun")
    if rc or rc2:
        say(out[-2000:] + out2[-2000:])
        sys.exit(1)
    for kind in ("ui", "app"):
        for i in range(1, a.n + 1):
            with open(files[kind], "a") as f:
                f.write(f"\nval warmTiming{kind.capitalize()}{i}: Int = {i}\n")
            rc, out, dt = gradle("hotReload")
            t = TIMING.search(out)
            w = WARM_OK.search(out)
            ok = rc == 0 and "WARM top-level-property" in out and t and w
            row = dict(kind=kind, i=i, ok=bool(ok), wall=dt,
                       warm=int(w.group(1)) if w else None, pkg=int(w.group(2)) if w else None,
                       relaunch=int(w.group(3)) if w else None,
                       compile=int(t.group(2)) if t else None, merge=int(t.group(3)) if t else None,
                       embed_bundle=int(t.group(4)) if t else None,
                       embed=int(t.group(5)) if t and t.group(5) else None, bundle=int(t.group(6)) if t and t.group(6) else None,
                       baseline_wait=int(t.group(7)) if t and t.group(7) else None)
            rows.append(row)
            say(f"{kind}[{i}] {'OK' if ok else 'FAIL'} warm={row['warm']} package={row['pkg']} relaunch={row['relaunch']} | "
                f"compile={row['compile']} merge={row['merge']} embed+bundle={row['embed_bundle']} embed={row['embed']} "
                f"bundle={row['bundle']} baseline-wait={row['baseline_wait']} | {t.group(0) if t else '-'}")
            if not ok:
                say("\n".join(out.splitlines()[-30:]))
finally:
    for k, f in files.items():
        open(f, "w").write(orig[k])
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, a.bundle], capture_output=True)
    gradle("hotReloadDaemonStop")
    say("# sources restored; app and compile daemons stopped")


def med(kind, key):
    v = [r[key] for r in rows if r["kind"] == kind and r[key] is not None]
    return f"{statistics.median(v) / 1000:.1f}" if v else "-"


for kind in ("ui", "app"):
    n_ok = sum(1 for r in rows if r["kind"] == kind and r["ok"])
    say(f"median {kind} ({n_ok}/{a.n} ok): warm {med(kind, 'warm')} s, package {med(kind, 'pkg')} s "
        f"(compile {med(kind, 'compile')}, merge {med(kind, 'merge')}, embed+bundle {med(kind, 'embed_bundle')}, "
        f"embed {med(kind, 'embed')}, bundle {med(kind, 'bundle')}, baseline-wait {med(kind, 'baseline_wait')}), relaunch {med(kind, 'relaunch')} s")
failed = [r for r in rows if not r["ok"]]
say(f"summary: {len(rows) - len(failed)}/{len(rows)} warm restarts measured")
sys.exit(1 if failed or len(rows) < 2 * a.n else 0)
