#!/usr/bin/env python3
"""0.2.0 acceptance on the official KMP wizard template (iOS only, Compose Multiplatform UI).

Starts from the untouched wizard zip, adds the test fixture a user project would have (one expect/actual pair
and kotlinx-datetime from Maven Central), then applies only the steps of docs/TRYOUT.md section 7 and checks:

  package -> run (EH check, STUB-SEL) -> real tap shows the greeting
  commonMain body -> HOT; commonMain use of kotlinx-datetime -> HOT; iosMain actual body -> HOT
  expect signature -> WARM and back up; HOT again in the new process
  add an expect/actual pair -> WARM; remove it -> WARM
  counter-examples: klibs with edited manifests (ABI 2.7.0, metadata 2.7.0, ABI 1.4.1) stop the build before compiling
  release: hotReloadVerifyRelease passes; the KMP plugin's release/debug task graphs are identical with and without the plugin

Every reload is followed by real taps (hide and show the greeting with "Click me!") and an on-screen check (AXe).
The only deviation from TRYOUT: the plugin repository is a local copy of the Pages Maven dir (0.2.0 is not published yet).

Usage: accept-kmp.py --zip WIZARD.zip --work DIR --plugin-repo URL --toolchain DIR --udid U [--port 5568] [--plist-workaround]
Stops what it started (the app by bundle id, the project's compile daemons) at the end.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

ap = argparse.ArgumentParser()
ap.add_argument("--zip", required=True)
ap.add_argument("--work", required=True)
ap.add_argument("--plugin-repo", required=True)
ap.add_argument("--toolchain", required=True, help="<TOOLCHAIN>/kotlin-native")
ap.add_argument("--udid", required=True)
ap.add_argument("--port", default="5568")
ap.add_argument("--plist-workaround", action="store_true",
                help="toolchain 2.5.255-hotreload.4 only: add the enforceStrictPlistSanityCheck line (TRYOUT section 7, known limitations)")
a = ap.parse_args()

WORK = os.path.abspath(a.work)
P = os.path.join(WORK, "KmpHr")
SH = os.path.join(P, "shared")
PKG = "src/{}/kotlin/org/example/kmphr"
EXE, BUNDLE = "KmpHr", "org.example.kmphr.hotreload"
LOG = os.path.join(SH, "launch.log")
HOME = os.path.expanduser("~")
GAP = "/tmp/gap-selrefs.log"
results = []


REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../.."))


def clean(text):
    return text.replace(WORK, "<WORK>").replace(REPO, "<REPO>").replace(HOME, "<HOME>")


def say(line=""):
    print(clean(line), flush=True)


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    say(f"{'PASS' if ok else 'FAIL'} {name}" + (f" | {detail}" if detail else ""))
    return ok


def path(src, name):
    return os.path.join(SH, PKG.format(src), name)


def edit(f, old, new):
    s = open(f).read()
    if old not in s:
        raise KeyError(f"{old!r} not in {f}")
    open(f, "w").write(s.replace(old, new, 1))


def write(f, text):
    os.makedirs(os.path.dirname(f), exist_ok=True)
    open(f, "w").write(text)


def gradle(*args, timeout=1200):
    t0 = time.time()
    r = subprocess.run([os.path.join(P, "gradlew"), "-p", P, *args, "--console=plain"], cwd=P,
                       capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout + r.stderr, time.time() - t0


def grep(out, pattern):
    return [l for l in out.splitlines() if re.search(pattern, l)]


# ---------------------------------------------------------------- simulator (AXe), as in lambda_stress.py
def walk(ns):
    for n in ns:
        yield n
        yield from walk(n.get("children", []))


def tree():
    r = subprocess.run(["axe", "describe-ui", "--udid", a.udid], capture_output=True, text=True)
    try:
        d = json.loads(r.stdout) if r.returncode == 0 else []
    except ValueError:
        d = []
    return d if isinstance(d, list) else [d]


def labels():
    return [n.get("AXLabel") or "" for n in walk(tree())]


def wait(pred, timeout=30):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if pred(labels()):
            return True
        time.sleep(0.3)
    return False


def tap(label):
    t = tree()
    app = next(n for n in walk(t) if n.get("type") == "Application")
    af = app["frame"]
    b = [n for n in walk(t) if n.get("type") == "Button" and n.get("AXLabel") == label][0]["frame"]
    sw, sh = 402.0, 874.0
    s = min(sw / af["width"], sh / af["height"])
    ox, oy = (sw - af["width"] * s) / 2, (sh - af["height"] * s) / 2
    x, y = round(ox + (b["x"] + b["width"] / 2) * s, 1), round(oy + (b["y"] + b["height"] / 2) * s, 1)
    subprocess.run(["axe", "tap", "-x", str(x), "-y", str(y), "--udid", a.udid], capture_output=True, check=True)


def greeting():
    return next((l for l in labels() if l.startswith("Compose: ")), None)


def show_greeting(expect):
    """Real taps: hide the greeting if shown, then show it again (the template computes it in remember {})."""
    if greeting() is not None:
        tap("Click me!")
        wait(lambda ls: not any(l.startswith("Compose: ") for l in ls), timeout=10)
    tap("Click me!")
    ok = wait(lambda ls: any(l.startswith("Compose: ") and all(e in l for e in expect) for l in ls), timeout=20)
    return ok, greeting()


def pid():
    o = subprocess.run(["pgrep", "-f", f"Devices/{a.udid}/.*/{EXE}.app/{EXE}"], capture_output=True, text=True).stdout.split()
    return o[0] if o else None


def launch_checks(tag):
    log = open(LOG, errors="replace").read() if os.path.exists(LOG) else ""
    eh = re.findall(r"EH check: (\d+) FDE.*?(\d+) pc-out-of-range", log)
    sel = re.findall(r"STUB-SEL check: (\d+) stubs, (\d+) mismatch", log)
    check(f"{tag}: EH check FDE > 0, 0 pc-out-of-range", eh and int(eh[0][0]) > 0 and eh[0][1] == "0", f"{eh[:1]}")
    check(f"{tag}: SELREF stub check 0 mismatch", sel and all(m == "0" for _, m in sel), f"{sel}")
    crash = re.search(r"Uncaught|EXC_|Terminating app|SIGSEGV|SIGBUS", log)
    check(f"{tag}: no crash in launch.log", not crash, crash.group(0) if crash else "")


def reload(name, expect_kind, screen, *, warm_reason=None):
    p0 = pid()
    rc, out, dt = gradle("hotReload")
    kind = "HOT" if "compat: HOT" in out else ("WARM" if "compat: WARM" in out else "?")
    warm = grep(out, r"^WARM ")
    detail = f"{dt:.1f}s exit={rc} compat={kind} pid {p0}->{{p1}}" + (f" {warm}" if warm else "")
    if rc != 0:
        say("\n".join(out.splitlines()[-25:]))
    ok = rc == 0 and kind == expect_kind
    if expect_kind == "HOT":
        ok = ok and "app loaded the patch" in out
    else:
        ok = ok and any(l.startswith("WARM OK") for l in warm)
        if warm_reason:
            ok = ok and any(re.search(warm_reason, l) for l in warm)
    shown, g = show_greeting(screen)
    p1 = pid()
    same = p1 == p0
    ok = ok and shown and (same if expect_kind == "HOT" else (p1 is not None and p1 != p0))
    check(f"{name} -> {expect_kind}", ok, detail.format(p1=p1) + f" screen={g!r}")
    if expect_kind == "WARM":
        launch_checks(f"{name} (after warm restart)")
    return ok


# ---------------------------------------------------------------- 0. fresh template
say(f"# KMP template acceptance, {time.strftime('%F %T')}, simulator {a.udid}, port {a.port}")
say(f"# wizard zip sha256 {hashlib.sha256(open(a.zip, 'rb').read()).hexdigest()}")
say(f"# plugin repository {a.plugin_repo}")
if os.path.exists(WORK):
    shutil.rmtree(WORK)
os.makedirs(WORK)
zipfile.ZipFile(a.zip).extractall(WORK)
os.chmod(os.path.join(P, "gradlew"), 0o755)
gap_before = os.path.getmtime(GAP) if os.path.exists(GAP) else None

def main():
    # ---------------------------------------------------------------- 1. test fixture (the user's own project)
    say("## 1. fixture: one expect/actual pair and kotlinx-datetime 0.7.1 (Maven Central) in the wizard template")
    toml = os.path.join(P, "gradle/libs.versions.toml")
    edit(toml, "[versions]\n", '[versions]\nkotlinx-datetime = "0.7.1"\n')
    edit(toml, "[libraries]\n", '[libraries]\nkotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinx-datetime" }\n')
    build = os.path.join(SH, "build.gradle.kts")
    edit(build, "implementation(libs.compose.runtime)", "implementation(libs.compose.runtime)\n            implementation(libs.kotlinx.datetime)")
    EXTRAS, EXTRAS_IOS = path("commonMain", "Extras.kt"), path("iosMain", "Extras.ios.kt")
    write(EXTRAS, "package org.example.kmphr\n\nimport kotlinx.datetime.DatePeriod\nimport kotlinx.datetime.LocalDate\nimport kotlinx.datetime.plus\n\n"
                  "expect fun platformTag(): String\n\nfun dateLine(): String = LocalDate(2026, 11, 19).plus(DatePeriod(days = 1)).toString()\n")
    write(EXTRAS_IOS, 'package org.example.kmphr\n\nactual fun platformTag(): String = "tag-ios-1"\n')
    APP = path("commonMain", "App.kt")
    edit(APP, 'Text("Compose: $greeting")', 'Text("Compose: $greeting | ${platformTag()} | ${dateLine()}")')

    # ---------------------------------------------------------------- 2. TRYOUT section 7, steps 1-4
    say("## 2. TRYOUT section 7: plugin repository, plugin line, local.properties, .gitignore")
    edit(os.path.join(P, "settings.gradle.kts"), "pluginManagement {\n    repositories {\n",
         f'pluginManagement {{\n    repositories {{\n        maven {{ url = uri("{a.plugin_repo}") }}\n')
    edit(build, "plugins {\n", 'plugins {\n    id("com.solufee.cmp.hotreload") version "0.2.0"\n')
    write(os.path.join(P, "local.properties"),
          f"cmp.hotreload.toolchainDir={a.toolchain}\ncmp.hotreload.simulatorUdid={a.udid}\ncmp.hotreload.port={a.port}\n")
    if a.plist_workaround:
        say("   (toolchain .4 workaround: enforceStrictPlistSanityCheck = false in MainViewController.kt)")
        edit(path("iosMain", "MainViewController.kt"), "ComposeUIViewController {", "ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) {")
    else:
        say("   MainViewController.kt unchanged")
    with open(os.path.join(P, ".gitignore"), "a") as g:
        g.write("\nlocal.properties\nshared/.hr/\nshared/launch.log\n")

    # ---------------------------------------------------------------- 2b. release baseline (before any hot reload)
    say("## 2b. release baseline: hotReloadVerifyRelease -Pcmp.hotreload.releaseBaseline=true, sources snapshot")
    shutil.copytree(os.path.join(SH, "src"), os.path.join(WORK, "src-baseline"))
    rc, out, dt = gradle("hotReloadVerifyRelease", "-Pcmp.hotreload.releaseBaseline=true", timeout=2400)
    for l in grep(out, r"release|BUILD|wrong"):
        say("  " + l)
    check("release baseline recorded", rc == 0, f"{dt:.0f}s")

    # ---------------------------------------------------------------- 3. package and run
    say("## 3. ./gradlew hotReloadPackage, ./gradlew hotReloadRun")
    rc, out, dt = gradle("hotReloadPackage", timeout=1800)
    for l in grep(out, r"toolchain OK|dependency klibs|hot packages|bundle check|package timing|BUILD|rror"):
        say("  " + l)
    check("package", rc == 0 and "package timing" in out, f"{dt:.0f}s")
    if rc != 0:
        say("\n".join(out.splitlines()[-40:]))
        sys.exit(1)
    rc, out, dt = gradle("hotReloadPackage")
    check("package again (no change)", rc == 0, f"{dt:.1f}s; " + "; ".join(grep(out, r"package timing")))
    rc, out, dt = gradle("hotReloadRun")
    for l in grep(out, r"app started|BUILD|wrong|port"):
        say("  " + l)
    check("run", rc == 0 and "app started" in out, f"{dt:.1f}s pid {pid()}")
    launch_checks("run")
    ok, g = show_greeting(["Hello, iOS", "tag-ios-1", "2026-11-20"])
    check("tap 'Click me!' shows the greeting (commonMain + iosMain actual + kotlinx-datetime)", ok, repr(g))

    # ---------------------------------------------------------------- 4. hot and warm changes
    say("## 4. changes")
    GREET = path("commonMain", "GreetingUtil.kt")
    edit(GREET, '"Hello, ', '"Hello again, ')
    reload("commonMain body (Greeting.greet)", "HOT", ["Hello again, iOS", "tag-ios-1"])
    edit(EXTRAS, "DatePeriod(days = 1)", "DatePeriod(days = 2)")
    reload("commonMain body using kotlinx-datetime (dateLine)", "HOT", ["2026-11-21"])
    edit(EXTRAS_IOS, '"tag-ios-1"', '"tag-ios-2"')
    reload("iosMain actual body (platformTag)", "HOT", ["tag-ios-2"])
    edit(EXTRAS, "expect fun platformTag(): String", "expect fun platformTag(n: Int = 0): String")
    edit(EXTRAS_IOS, 'actual fun platformTag(): String = "tag-ios-2"', 'actual fun platformTag(n: Int): String = "tag-ios-3"')
    reload("expect/actual signature (platformTag(n: Int = 0))", "WARM", ["tag-ios-3", "Hello again", "2026-11-21"], warm_reason=r"platformTag")
    edit(EXTRAS_IOS, '"tag-ios-3"', '"tag-ios-4"')
    reload("after the warm restart: iosMain actual body", "HOT", ["tag-ios-4"])
    edit(EXTRAS, "fun dateLine()", "expect fun flavor(): String\n\nfun dateLine()")
    edit(EXTRAS_IOS, "actual fun platformTag", 'actual fun flavor(): String = "sim"\n\nactual fun platformTag')
    reload("add an expect/actual pair (flavor)", "WARM", ["tag-ios-4"], warm_reason=r"^WARM expect-actual flavor: expect fun added")
    edit(EXTRAS, "expect fun flavor(): String\n\n", "")
    edit(EXTRAS_IOS, 'actual fun flavor(): String = "sim"\n\n', "")
    reload("remove the expect/actual pair (flavor)", "WARM", ["tag-ios-4"], warm_reason=r"^WARM expect-actual flavor: expect fun removed")
    edit(GREET, '"Hello again, ', '"Hello once more, ')
    reload("commonMain body after both warm restarts", "HOT", ["Hello once more, iOS", "tag-ios-4"])

    # ---------------------------------------------------------------- 5. counter-examples: klibs with edited manifests
    say("## 5. klibs with edited manifests (extraKlibs) stop the build before compiling")
    src = next(l.strip() for l in open(os.path.join(os.path.dirname(a.toolchain), "klibs.txt")) if "kotlinx-datetime" in l)
    fake = os.path.join(WORK, "fake-klibs")
    os.makedirs(fake)


    def edited(name, **fields):
        out = os.path.join(fake, f"{name}.klib")
        with zipfile.ZipFile(src) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename.endswith("default/manifest"):
                    text = data.decode()
                    text = re.sub(r"(?m)^unique_name=.*$", f"unique_name=org.example\\\\:{name}", text)
                    for k, v in fields.items():
                        text = re.sub(rf"(?m)^{k}=.*$", f"{k}={v}", text) if re.search(rf"(?m)^{k}=", text) else text + f"\n{k}={v}\n"
                    data = text.encode()
                zout.writestr(item, data)
        return out


    cases = {
        "future-abi": (edited("future-abi", abi_version="2.7.0"), r"future-abi.*built for klib ABI 2\.7\.0, the toolchain's compiler reads up to 2\.5\.0"),
        "future-metadata": (edited("future-metadata", metadata_version="2.7.0"), r"future-metadata.*klib metadata 2\.7\.0 is newer than the toolchain's compiler can read \(up to 2\.6\.0\)"),
        "ancient-abi": (edited("ancient-abi", abi_version="1.4.1"), r"ancient-abi.*built for klib ABI 1\.4\.1, older than the oldest the compiler reads \(1\.6\.0"),
    }
    build_text = open(build).read()
    for name, (klib, pattern) in cases.items():
        open(build, "w").write(build_text + f'\ncmpHotReload {{\n    extraKlibs.from("{klib}")\n}}\n')
        rc, out, dt = gradle("hotReloadPackage")
        hit = grep(out, pattern)
        compiled = grep(out, r"compile: daemon klib OK|package timing")
        check(f"edited manifest {name}: build stops with the library named", rc != 0 and hit and not compiled,
              (hit[0].strip() if hit else "\n".join(out.splitlines()[-15:])))
    open(build, "w").write(build_text)
    rc, out, dt = gradle("hotReloadPackage")
    check("package again without the edited klibs", rc == 0, f"{dt:.1f}s")

    # ---------------------------------------------------------------- 6. release unchanged
    say("## 6. release: sources back to the baseline state, then hotReloadVerifyRelease (daemons still running)")
    shutil.rmtree(os.path.join(SH, "src"))
    shutil.copytree(os.path.join(WORK, "src-baseline"), os.path.join(SH, "src"))
    rc, out, dt = gradle("hotReloadVerifyRelease", timeout=1800)
    for l in grep(out, r"release|BUILD|wrong|differ"):
        say("  " + l)
    check("hotReloadVerifyRelease (ReleaseCheck)", rc == 0, f"{dt:.0f}s")
    GRAPH = [":shared:linkReleaseFrameworkIosArm64", ":shared:linkReleaseFrameworkIosSimulatorArm64",
             ":shared:linkDebugFrameworkIosSimulatorArm64"]


    def graph():
        rc, out, _ = gradle(*GRAPH, "--dry-run")
        return rc, [l for l in out.splitlines() if l.startswith(":") and l.endswith("SKIPPED")]


    rc1, with_plugin = graph()
    open(build, "w").write(build_text.replace('    id("com.solufee.cmp.hotreload") version "0.2.0"\n', ""))
    rc2, without = graph()
    open(build, "w").write(build_text)
    check("Xcode/KMP framework task graphs identical with and without the plugin (dry run)",
          rc1 == 0 and rc2 == 0 and with_plugin == without and len(with_plugin) > 10,
          f"{len(with_plugin)} tasks with, {len(without)} without")



def stop():
    p_last = pid()
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
    if os.path.exists(os.path.join(P, "local.properties")):
        gradle("hotReloadDaemonStop")
    say(f"terminated {BUNDLE} (pid {p_last}); compile daemons stopped; app running now: {pid() is not None}")


try:
    main()
except Exception as e:
    check(f"script error: {type(e).__name__}: {e}", False)
finally:
    say("## 7. stop")
    stop()
# ---------------------------------------------------------------- 7. stop and summary
gap_after = os.path.getmtime(GAP) if os.path.exists(GAP) else None
check("libKaldo wrote no SELREF debug log (/tmp/gap-selrefs.log unchanged)", gap_after == gap_before, f"{gap_before} -> {gap_after}")
failed = [n for n, ok in results if not ok]
say(f"summary: {len(results) - len(failed)}/{len(results)} PASS" + ("" if not failed else f"; FAILED: {failed}"))
sys.exit(1 if failed else 0)
