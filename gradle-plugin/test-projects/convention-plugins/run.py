#!/usr/bin/env python3
"""#76: the convention-plugin project (setup.py) on the simulator.

  1  cold start          hotReloadPackage + hotReloadRun; Koin injects across both modules, serialization round trips
  2  UI edit             UI_TAG u1 -> u2: HOT, same process
  3  @Serializable field SavedServer (:core) gets `port: Int = 443`: WARM; after the restart the JSON carries "port":443
  3b @Rpc interface   ServerApi (:core) gets a method: the descriptor the rpc plugin generates lists it (verdict recorded)
  4  add @Single         CoreExtra (:core) becomes a definition: WARM; after the restart Koin injects it (extra=k1)
  5  remove @Single      back to no definition: WARM; after the restart extra=none
  6  body after restart  CoreExtra body k1 -> k2 while it is a definition again (re-added): new code injected
  7  --stress N          N body edits of CoreExtra (:core, @Single) and of GreeterImpl (:shared, @Factory): HOT, same process (Issue #78)

The app prints one line per composition into the launch log:
  BOTAS ui=<UI_TAG> greet=<Greeter> extra=<CoreExtra or none> rpc=<ServerApi calls> server=<SavedServer round trip> nav=<NavKey json>

Usage: run.py PROJECT_DIR --udid U
"""
import argparse
import os
import re
import subprocess
import time

ap = argparse.ArgumentParser()
ap.add_argument("project")
ap.add_argument("--udid", required=True)
ap.add_argument("--stress", type=int, default=0, help="after step 6b, N body edits of a DI-created class in :core and N in :shared (Issue #78)")
a = ap.parse_args()
P = os.path.abspath(a.project)
BUNDLE, EXE = "org.example.kmphr.hotreload", "KmpHr"
LOG = os.path.join(P, "shared/launch.log")
SHARED = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr")
CORE = os.path.join(P, "core/src/commonMain/kotlin/org/example/core")
HOME = os.path.expanduser("~")
REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../.."))
checks = []


def clean(t):
    return t.replace(P, "<PROJECT>").replace(REPO, "<REPO>").replace(HOME, "<HOME>")


def say(t=""):
    print(clean(t), flush=True)


def check(name, ok, detail=""):
    checks.append((name, ok))
    say(f"  {'PASS' if ok else 'FAIL'} {name}" + (f" ({detail})" if detail else ""))


def gradle(*args):
    t0 = time.time()
    r = subprocess.run([os.path.join(P, "gradlew"), "-p", P, *args, "--console=plain"], cwd=P, capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr, time.time() - t0


def pid():
    o = subprocess.run(["pgrep", "-f", f"Devices/{a.udid}/.*/{EXE}.app/{EXE}"], capture_output=True, text=True).stdout.split()
    return o[0] if o else None


def lines():
    try:
        return [l.strip() for l in open(LOG, errors="replace").read().replace("\r", "\n").splitlines() if l.startswith("BOTAS ")]
    except OSError:
        return []


def wait_line(pred, timeout=25):
    end = time.time() + timeout
    while time.time() < end:
        ls = lines()
        if ls and pred(ls[-1]):
            return ls[-1], True
        time.sleep(0.5)
    ls = lines()
    return (ls[-1] if ls else "(no BOTAS line)"), False


def keep(out):
    pats = ("compat:", "WARM ", "HOT", "sent ", "app loaded", "NOT applied", "error:", "e: ", "FAILURE", "BUILD ", "app started",
            "warning: ", "Exception", "compiler plugin")
    return [l for l in out.splitlines() if any(p in l for p in pats)][:25]


def klib_has(directory, marker):
    """True if a klib under [directory] holds [marker] in any entry."""
    import zipfile
    for root, _, files in os.walk(directory):
        for f in files:
            if f.endswith(".klib"):
                with zipfile.ZipFile(os.path.join(root, f)) as z:
                    if any(marker in z.read(n) for n in z.namelist()):
                        return True
    return False


def edit(path, old, new):
    s = open(path).read()
    if old not in s:
        raise RuntimeError(f"{old!r} not in {path}")
    open(path, "w").write(s.replace(old, new, 1))


def step(name, fn, want, verdict_want, same_pid, task="hotReload"):
    say(f"## {name}")
    if fn:
        fn()
    p0 = pid()
    n0 = len(lines())
    rc, out, dt = gradle(*task.split())
    verdict = "HOT" if "compat: HOT" in out else ("WARM" if re.search(r"^WARM |compat: .*warm|WARM OK", out, re.M) else "-")
    line, reached = wait_line(want)
    p1 = pid()
    say(f"RESULT {name}: rc={rc} {dt:.1f}s verdict={verdict} pid {p0}->{p1} ({'same' if p0 == p1 else 'changed'}) new BOTAS lines={len(lines()) - n0}")
    say(f"  app: {line}")
    for l in keep(out):
        say("  | " + l)
    check(f"{name}: gradle rc=0", rc == 0)
    if verdict_want:
        check(f"{name}: verdict {verdict_want}", verdict == verdict_want, f"got {verdict}")
    if same_pid is not None:
        check(f"{name}: process {'kept' if same_pid else 'restarted'}", p1 is not None and (p0 == p1) == same_pid, f"{p0}->{p1}")
    check(f"{name}: app shows the expected state", reached, line[:160])
    return out


say(f"# #76 convention-plugin project, {time.strftime('%F %T')}, simulator {a.udid}")
rc, out, dt = gradle(":shared:compileKotlinIosSimulatorArm64")
say(f"RESULT normal build (KGP :shared:compileKotlinIosSimulatorArm64): rc={rc} {dt:.0f}s")
check("normal KGP build through the conventions", rc == 0)
rc, out, dt = gradle("hotReloadPackage")
say(f"RESULT package: rc={rc} {dt:.0f}s")
for l in [l for l in out.splitlines() if "package timing" in l or "compiler plugin" in l or "error" in l.lower() or "e: " in l][:25]:
    say("  | " + l)
check("hotReloadPackage", rc == 0)
# Each module's hot reload klib carries what the compiler plugins generated for it (Koin's per-module component-scan
# hints, rpc's service stub): proof that the plugins reached both modules' hot reload compiles.
# (library modules: build/hotreload/klib/<module>/; the app module: the classifier's build/hotreload/compat/app.klib)
hr = os.path.join(P, "shared/build/hotreload")
for name, rel, marker in ((":core", "klib/core", b"componentscan_org_example_core_CoreModule"),
                          (":core", "klib/core", b"$rpcServiceStub"),
                          (":shared", "compat", b"componentscan_org_example_kmphr_di_AppModule")):
    check(f"{name} hot reload klib has the plugin-generated {marker.decode()}", klib_has(os.path.join(hr, rel), marker))
if rc:
    say(out[-3000:])
else:
    step("1) cold start", None, lambda l: "ui=u1" in l and "greet=g1" in l and "extra=none" in l
         and "rpc=ping" in l and '"host":"h1","name":"n1"' in l and '"id":3' in l, None, None, task="hotReloadRun")
    step("2) UI edit (UI_TAG u1 -> u2)", lambda: edit(os.path.join(SHARED, "App.kt"), 'UI_TAG = "u1"', 'UI_TAG = "u2"'),
         lambda l: "ui=u2" in l, "HOT", True)
    step("3) @Serializable field added in :core (SavedServer + port: Int = 443)",
         lambda: edit(os.path.join(CORE, "SavedServer.kt"), "val name: String)", "val name: String, val port: Int = 443)"),
         lambda l: '"port":443' in l and "port=443" in l, "WARM", False)
    step("3b) @Rpc interface in :core gets a method (kotlinx.rpc generates a stub class inside it)",
         lambda: edit(os.path.join(CORE, "ServerApi.kt"), "    suspend fun ping(host: String): String\n",
                      "    suspend fun ping(host: String): String\n    suspend fun status(): String\n"),
         lambda l: "rpc=ping,status" in l, None, None)
    step("4) add @Single in :core (CoreExtra)", lambda: edit(os.path.join(CORE, "CoreDi.kt"), "class CoreExtra {", "@Single\nclass CoreExtra {"),
         lambda l: "extra=k1" in l, "WARM", False)
    step("5) remove @Single in :core (CoreExtra)", lambda: edit(os.path.join(CORE, "CoreDi.kt"), "@Single\nclass CoreExtra {", "class CoreExtra {"),
         lambda l: "extra=none" in l, "WARM", False)
    step("6a) add @Single again", lambda: edit(os.path.join(CORE, "CoreDi.kt"), "class CoreExtra {", "@Single\nclass CoreExtra {"),
         lambda l: "extra=k1" in l, "WARM", False)
    step("6b) CoreExtra body k1 -> k2", lambda: edit(os.path.join(CORE, "CoreDi.kt"), 'fun tag(): String = "k1"', 'fun tag(): String = "k2"'),
         lambda l: "extra=k2" in l, None, None)
if not rc and a.stress:
    # Issue #78: body edits of classes Koin creates (CoreExtra @Single in :core, GreeterImpl @Factory in :shared), each
    # one HOT in the same process with the new value on screen
    for i in range(a.stress):
        step(f"7.{i + 1}a) :core CoreExtra body k{i + 2} -> k{i + 3}",
             lambda i=i: edit(os.path.join(CORE, "CoreDi.kt"), f'fun tag(): String = "k{i + 2}"', f'fun tag(): String = "k{i + 3}"'),
             lambda l, i=i: f"extra=k{i + 3} " in l, "HOT", True)
        step(f"7.{i + 1}b) :shared GreeterImpl body g{i + 1} -> g{i + 2}",
             lambda i=i: edit(os.path.join(SHARED, "di/Di.kt"), f'fun greet(): String = "g{i + 1}"', f'fun greet(): String = "g{i + 2}"'),
             lambda l, i=i: f"greet=g{i + 2} " in l, "HOT", True)
subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
gradle("hotReloadDaemonStop")
say("# app and compile daemons stopped")
n = sum(1 for _, ok in checks if ok)
say(f"summary: {n}/{len(checks)} PASS")
