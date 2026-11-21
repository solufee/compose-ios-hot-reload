#!/usr/bin/env python3
"""#75: one compat group (see setup.py) through plugin 0.2.7 on the simulator: package, run, then edits with
`gradlew hotReload`; after each step the classifier verdict, the PIDs and the app's newest COMPAT line.

Usage: run-group.py GROUP PROJECT_DIR --udid U   (PROJECT_DIR already prepared with setup.py)
"""
import argparse
import os
import re
import subprocess
import sys
import time

ap = argparse.ArgumentParser()
ap.add_argument("group")
ap.add_argument("project")
ap.add_argument("--udid", required=True)
a = ap.parse_args()
G, P = a.group, os.path.abspath(a.project)
BUNDLE, EXE = "org.example.kmphr.hotreload", "KmpHr"
LOG = os.path.join(P, "shared/launch.log")
PKG = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr")
HOME = os.path.expanduser("~")
REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../.."))


def clean(t):
    return t.replace(P, "<PROJECT>").replace(REPO, "<REPO>").replace(HOME, "<HOME>")


def say(t=""):
    print(clean(t), flush=True)


def gradle(*args):
    t0 = time.time()
    r = subprocess.run([os.path.join(P, "gradlew"), "-p", P, *args, "--console=plain"], cwd=P, capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr, time.time() - t0


def pid():
    o = subprocess.run(["pgrep", "-f", f"Devices/{a.udid}/.*/{EXE}.app/{EXE}"], capture_output=True, text=True).stdout.split()
    return o[0] if o else None


def compat_lines():
    try:
        return [l.strip() for l in open(LOG, errors="replace").read().replace("\r", "\n").splitlines() if l.startswith("COMPAT ")]
    except OSError:
        return []


def wait_compat(pred, timeout=25):
    end = time.time() + timeout
    while time.time() < end:
        ls = compat_lines()
        if ls and pred(ls[-1]):
            return ls[-1]
        time.sleep(0.5)
    ls = compat_lines()
    return (ls[-1] + "  [expected state not reached]") if ls else "(no COMPAT line)"


def keep(out):
    pats = ("compat:", "WARM ", "HOT", "sent ", "app loaded", "NOT applied", "error:", "e: ", "FAILURE", "BUILD ", "app started",
            "Unresolved", "warning: ", "exception", "Exception")
    return [l for l in out.splitlines() if any(p in l for p in pats)][:25]


def edit(rel, old, new):
    p = os.path.join(PKG, rel)
    s = open(p).read()
    if old not in s:
        raise RuntimeError(f"{old!r} not in {rel}")
    open(p, "w").write(s.replace(old, new, 1))


def step(name, fn, task="hotReload", want=None):
    say(f"## {name}")
    if fn:
        fn()
    p0 = pid()
    n0 = len(compat_lines())
    rc, out, dt = gradle(*task.split())
    verdict = "HOT" if "compat: HOT" in out else ("WARM" if re.search(r"^WARM |compat: .*warm|WARM OK", out, re.M) else "-")
    line = wait_compat(want) if want else (compat_lines()[-1] if compat_lines() else "(none)")
    p1 = pid()
    say(f"RESULT {name}: rc={rc} {dt:.1f}s verdict={verdict} pid {p0}->{p1} ({'same' if p0 == p1 else 'changed'}) new COMPAT lines={len(compat_lines()) - n0}")
    say(f"  app: {line}")
    for l in keep(out):
        say("  | " + l)
    return rc, out


say(f"# #75 compat group {G}, {time.strftime('%F %T')}, project {os.path.basename(P)}, simulator {a.udid}")
rc, out, dt = gradle(":shared:compileKotlinIosSimulatorArm64")
say(f"RESULT normal build (KGP :shared:compileKotlinIosSimulatorArm64): rc={rc} {dt:.0f}s")
for l in [l for l in out.splitlines() if l.startswith("e: ") or "error:" in l or "FAILURE" in l][:15]:
    say("  | " + l)
rc, out, dt = gradle("hotReloadPackage")
say(f"RESULT package: rc={rc} {dt:.0f}s")
for l in [l for l in out.splitlines() if "package timing" in l or "error" in l.lower() or "e: " in l][:25]:
    say("  | " + l)
if rc:
    say(out[-3000:])
    sys.exit(1)
step("run", None, task="hotReloadRun", want=lambda l: "ui=u1" in l)
step("b) Compose UI text (UI_TAG u1 -> u2)", lambda: edit("App.kt", 'UI_TAG = "u1"', 'UI_TAG = "u2"'), want=lambda l: "ui=u2" in l)
if G in "BDE":
    step("c1) Koin factory implementation body (greet g1 -> g2)",
         lambda: edit("di/Di.kt", 'fun greet(): String = "g1"', 'fun greet(): String = "g2"'), want=lambda l: "greet=g2" in l)
if G in "BDE":
    step("c2) add a single (@Single on Extra), hotReload" + (" (KSP reruns through the source set dependency)" if G != "E" else " (Koin compiler plugin)"),
         lambda: (edit("di/Di.kt", "import org.koin.core.annotation.Module\n", "import org.koin.core.annotation.Module\nimport org.koin.core.annotation.Single\n"),
                  edit("di/Di.kt", "class Extra {", "@Single\nclass Extra {")), want=lambda l: "extra=x1" in l)
    gen = os.path.join(P, "shared/build/generated/ksp/iosSimulatorArm64/iosSimulatorArm64Main/kotlin")
    if G != "E":
        step("e1) KSP rerun (kspKotlinIosSimulatorArm64) then hotReload", None, task="kspKotlinIosSimulatorArm64 hotReload", want=lambda l: "extra=x1" in l)
        say("  generated: " + clean(subprocess.run(["grep", "-rn", "Extra", gen], capture_output=True, text=True).stdout.strip()[:400]))
    step("c3) fresh start (hotReloadPackage + hotReloadRun) after the new single", None, task="hotReloadPackage hotReloadRun", want=lambda l: "extra=x1" in l)
    step("c4) Extra body x1 -> x2 (existing single instance, new code?)",
         lambda: edit("di/Di.kt", 'fun tag(): String = "x1"', 'fun tag(): String = "x2"'), want=lambda l: "extra=x2" in l)
    if G != "E":
        step("e2) remove the single (@Single off) + KSP rerun + hotReload",
             lambda: edit("di/Di.kt", "@Single\nclass Extra {", "class Extra {"), task="kspKotlinIosSimulatorArm64 hotReload", want=lambda l: "extra=none" in l)
if G == "H":
    step("h) Poko class used with another value (x -> y)",
         lambda: edit("Probe.kt", 'Pk(1, \"x\")', 'Pk(1, \"y\")'), want=lambda l: "b=y" in l)
    # a property added to the @Poko class: Poko's generated toString/equals must cover it after the reload
    # (Pk(1, y, 7) == Pk(1, y, 8) is false only with the new equals)
    step("h2) @Poko class gets a property (c: Int = 0); probe prints Pk(1, y, 7) and Pk(1, y, 7) == Pk(1, y, 8)",
         lambda: (edit("Probe.kt", "class Pk(val a: Int, val b: String)", "class Pk(val a: Int, val b: String, val c: Int = 0)"),
                  edit("Probe.kt", 'Pk(1, \"y\").toString() + \" eq=\" + (Pk(1, \"x\") == Pk(1, \"x\"))',
                       'Pk(1, \"y\", 7).toString() + \" eq=\" + (Pk(1, \"y\", 7) == Pk(1, \"y\", 8))')),
         want=lambda l: "c=7" in l and "eq=false" in l)
if G == "R":
    # the rpc compiler plugin generates a nested stub class ($rpcServiceStub) inside every @Rpc interface: a new method
    # changes the interface and its stub; the descriptor must list it after the reload
    step("r) @Rpc interface gets a method (suspend fun bye())",
         lambda: edit("Probe.kt", "    suspend fun hello(name: String): String\n", "    suspend fun hello(name: String): String\n    suspend fun bye(): String\n"),
         want=lambda l: "calls=bye,hello" in l)
if G in "FG":
    step("f) power-assert expression edited (x + 1 == 3 -> x + 2 == 4)",
         lambda: edit("Probe.kt", "require(x + 1 == 3)", "require(x + 2 == 4)"), want=lambda l: "x + 2 == 4" in l)
if G in "CD":
    step("d) @Serializable field added (Item: + c: Int = 7)",
         lambda: edit("Probe.kt", 'val b: String = "x")', 'val b: String = "x", val c: Int = 7)'), want=lambda l: '"c":7' in l or "ERR" in l)
subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
gradle("hotReloadDaemonStop")
say("# app and compile daemons stopped")
