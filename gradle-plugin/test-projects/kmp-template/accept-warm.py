#!/usr/bin/env python3
"""0.2.4 acceptance for a warm restart on the KMP wizard template with one library module (#18, #21).

The template (shared = app) gets a library module `core` and the change that started #18 (botas-app): a data class
gets a field and a composable a parameter. Steps:
  1  package, run
  2  app body only -> HOT; core's klib task is up to date (not compiled)
  3  data class field + composable parameter -> WARM: the full output is kept; with --expect-advice every WARM line
     is followed by one advice line and the run ends with the summary line (#18); with --expect-klib-reuse the
     baseline reuses the classifier's app klib and core's klib is not compiled (#21)
  3b with --expect-advice: signature changes only (a composable parameter, a top-level function parameter) -> HOT
     with the remember-reset note and no WARM reason, advice or summary (#28 rules; #18 advice must not claim them)
  4  core body -> HOT; with --expect-klib-reuse the log says why core's klib is compiled (#21)
  5  with --expect-advice: the same kind of change with --no-warm prints the summary "not restarted" (#18)

Usage: accept-warm.py --zip WIZARD.zip --work DIR --plugin-repo URL --plugin-version V --toolchain DIR --udid U
       [--port 5568] [--expect-advice] [--expect-klib-reuse]
Stops what it started (the app by bundle id, the project's compile daemons) at the end.
"""
import argparse
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
ap.add_argument("--plugin-version", required=True)
ap.add_argument("--toolchain", required=True)
ap.add_argument("--udid", required=True)
ap.add_argument("--port", default="5568")
ap.add_argument("--expect-advice", action="store_true")
ap.add_argument("--expect-klib-reuse", action="store_true")
a = ap.parse_args()

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "../../.."))
WORK = os.path.abspath(a.work)
P = os.path.join(WORK, "KmpHr")
HOME = os.path.expanduser("~")
BUNDLE = "org.example.kmphr.hotreload"
APP = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/App.kt")
SHAPE = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/Shape.kt")
CORE = os.path.join(P, "core/src/commonMain/kotlin/org/example/core/Core.kt")
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


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w").write(text)


def gradle(*args, timeout=1800):
    t0 = time.time()
    r = subprocess.run([os.path.join(P, "gradlew"), "-p", P, *args, "--console=plain"], cwd=P, capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout + r.stderr, time.time() - t0


def lines(out, pattern):
    return [l for l in out.splitlines() if re.search(pattern, l)]


def show(out, pattern):
    for l in lines(out, pattern):
        say("  | " + l)


KEEP = r"^(compat|WARM|    -> |warm:|klib |app klib|compile: daemon|package timing|changed objects|sent |app loaded|> Task :(shared|core):hotReload)"

say(f"# warm restart acceptance (#18, #21), plugin {a.plugin_version}, {time.strftime('%F %T')}, simulator {a.udid}")
if os.path.exists(WORK):
    shutil.rmtree(WORK)
os.makedirs(WORK)
zipfile.ZipFile(a.zip).extractall(WORK)
os.chmod(os.path.join(P, "gradlew"), 0o755)
edit(os.path.join(P, "settings.gradle.kts"), "pluginManagement {\n    repositories {\n",
     f'pluginManagement {{\n    repositories {{\n        maven {{ url = uri("{a.plugin_repo}") }}\n')
edit(os.path.join(P, "settings.gradle.kts"), 'include(":shared")', 'include(":shared")\ninclude(":core")')
build = os.path.join(P, "shared/build.gradle.kts")
edit(build, "plugins {\n", f'plugins {{\n    id("com.solufee.cmp.hotreload") version "{a.plugin_version}"\n')
edit(build, "implementation(libs.compose.runtime)", 'implementation(libs.compose.runtime)\n            implementation(project(":core"))')
write(os.path.join(P, "core/build.gradle.kts"), "plugins {\n    alias(libs.plugins.kotlinMultiplatform)\n}\n\nkotlin {\n    iosArm64()\n    iosSimulatorArm64()\n}\n")
write(CORE, 'package org.example.core\n\nfun coreLine(): String = "core-1"\n')
write(SHAPE, "package org.example.kmphr\n\ndata class Uniforms(val radius: Float, val spin: Boolean = true)\n")
edit(APP, '                Text("Click me!")\n            }\n',
     '                Text("Click me!")\n            }\n            Shell(Uniforms(1f))\n')
open(APP, "a").write('\n@Composable\nfun Shell(u: Uniforms, autoSpin: Boolean = true) {\n'
                     '    Text("Shell: r=${u.radius} spin=${u.spin} ${org.example.core.coreLine()} v1")\n}\n')
open(os.path.join(P, "local.properties"), "w").write(
    f"cmp.hotreload.toolchainDir={a.toolchain}\ncmp.hotreload.simulatorUdid={a.udid}\ncmp.hotreload.port={a.port}\n")
# Issue #66: `axe describe-ui` turns on the simulator's accessibility switch (ApplicationAccessibilityEnabled,
# AccessibilityEnabled; ~1.8 s more at every app start). The shared helper saves both keys before the first describe-ui
# and writes them back when the script ends, also after a failure (absent keys are written back as NO).
AX_HELPER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../../example-gradle/tools/sim-ax-keys.sh")


def ax_keys(cmd):
    """Run example-gradle/tools/sim-ax-keys.sh save|restore for this simulator. Returns (ok, line)."""
    r = subprocess.run(["bash", AX_HELPER, cmd, a.udid], capture_output=True, text=True)
    line = (r.stdout.strip() or r.stderr.strip()).splitlines()[-1:] or [f"sim-ax-keys.sh {cmd}: no output"]
    return r.returncode == 0, line[0]


say(ax_keys("save")[1])
try:
    say("## 1. package, run")
    rc, out, dt = gradle("hotReloadPackage")
    check("package", rc == 0, f"{dt:.0f}s " + "; ".join(lines(out, "package timing|rror"))[:300])
    if rc != 0:
        say("\n".join(out.splitlines()[-30:]))
        sys.exit(1)
    rc, out, dt = gradle("hotReloadRun")
    check("run", rc == 0 and "app started" in out)

    say("## 2. app body only")
    edit(APP, " v1\")", " v2\")")
    rc, out, dt = gradle("hotReload")
    show(out, KEEP)
    core_compiled = lines(out, r"^klib core: (compiling|<|/)|^> Task :shared:hotReloadCompileKlibCore$")
    check("app body -> HOT; core's klib task up to date, core not compiled", rc == 0 and "compat: HOT" in out and not core_compiled,
          f"{dt:.1f}s; " + "; ".join(lines(out, r"hotReloadCompileKlibCore")))

    say("## 3. a field in a data class and a composable parameter (the botas-app change) -> warm restart")
    edit(SHAPE, "data class Uniforms(val radius: Float, val spin: Boolean = true)",
         "data class Uniforms(val radius: Float, val rimColor: Long = 0xFF00FFL, val spin: Boolean = true)")
    edit(APP, "fun Shell(u: Uniforms, autoSpin: Boolean = true)", "fun Shell(u: Uniforms, rim: String = \"R\", autoSpin: Boolean = true)")
    edit(APP, " v2\")", " v3 $rim\")")
    rc, out, dt = gradle("hotReload")
    show(out, KEEP)
    warm = lines(out, r"^WARM (?!OK)")
    check("WARM, restarted (WARM OK)", rc == 0 and warm and any(l.startswith("WARM OK") for l in out.splitlines()), f"{dt:.1f}s, {len(warm)} reasons")
    if a.expect_advice:
        ol = out.splitlines()
        followed = all(i + 1 < len(ol) and ol[i + 1].startswith("    -> ") for i, l in enumerate(ol) if re.match(r"^WARM (?!OK)", l))
        check("#18: every WARM reason is followed by one advice line", followed and len(warm) >= 2)
        ok_ms = re.search(r"^WARM OK ms=(\d+)", out, re.M)
        summ = re.search(r"^compat: (\d+) ABI changes?, cannot hot-patch; warm restart took ([\d.]+) s$", out, re.M)
        last = [l for l in ol if l.strip() and not l.startswith(("BUILD", "Configuration cache", "Reusing", "Calculating", "1 actionable", "2 actionable", "3 actionable"))][-1:]
        check("#18: summary line with the reason count and the measured warm restart time",
              summ and ok_ms and int(summ.group(1)) == len(warm) and abs(float(summ.group(2)) * 1000 - int(ok_ms.group(1))) < 100,
              f"{summ.group(0) if summ else None}; WARM OK ms={ok_ms.group(1) if ok_ms else None}; last line {last}")
        check("#18: 'the app saved no state' points to the README section",
              any("the app saved no state" in l and "State across a warm restart" in l for l in ol))
    if a.expect_klib_reuse:
        check("#21: the baseline reuses the classifier's app klib (inputs unchanged)",
              any(l.startswith("app klib (classifier): inputs unchanged") for l in out.splitlines()),
              "; ".join(lines(out, r"^app klib|compile: daemon klib")))
        check("#21: core's klib is not compiled during the reload or the warm restart",
              not lines(out, r"^klib core: (compiling|<|/)"), "; ".join(lines(out, r"hotReloadCompileKlibCore|^klib core")))
    seen = subprocess.run(["axe", "describe-ui", "--udid", a.udid], capture_output=True, text=True).stdout
    check("after the warm restart the app shows the new code", "v3 R" in seen and "r=1.0" in seen)

    if a.expect_advice:
        say("## 3b. signature changes only (#28): hot, no warm advice")
        edit(APP, 'fun Shell(u: Uniforms, rim: String = "R", autoSpin: Boolean = true)', 'fun Shell(u: Uniforms, rim: String = "R", tilt: Int = 7, autoSpin: Boolean = true)')
        edit(APP, ' v3 $rim")', ' v4 $rim$tilt ${org.example.kmphr.sigTag()}")')
        open(SHAPE, "a").write('\nfun sigTag(): String = "t1"\n')
        rc, out, dt = gradle("hotReload")
        show(out, KEEP)
        check("#18/#28: composable parameter -> HOT with the remember-reset note, no WARM line, advice or summary",
              rc == 0 and "compat: HOT" in out and "app loaded the patch" in out and "compat: note: Shell changed its signature" in out
              and not lines(out, r"^WARM |^    -> |ABI change"), f"{dt:.1f}s")
        edit(SHAPE, 'fun sigTag(): String = "t1"', 'fun sigTag(suffix: String = "!"): String = "t2$suffix"')
        rc, out, dt = gradle("hotReload")
        show(out, KEEP)
        check("#18/#28: top-level function parameter -> HOT, no note, no WARM line, advice or summary",
              rc == 0 and "compat: HOT" in out and not lines(out, r"^WARM |^    -> |ABI change|^compat: note"), f"{dt:.1f}s")
        seen = subprocess.run(["axe", "describe-ui", "--udid", a.udid], capture_output=True, text=True).stdout
        check("the signature changes are on screen in the same app", "v4 R7 t2!" in seen)

    say("## 4. core body")
    edit(CORE, '"core-1"', '"core-2"')
    rc, out, dt = gradle("hotReload")
    show(out, KEEP)
    check("core body -> HOT", rc == 0 and "compat: HOT" in out and "app loaded the patch" in out, f"{dt:.1f}s")
    if a.expect_klib_reuse:
        check("#21: the log says why core's klib is compiled", any(l.startswith("klib core: compiling (sources changed: Core.kt)") for l in out.splitlines()),
              "; ".join(lines(out, r"^klib core")))

    if a.expect_advice:
        say("## 5. --no-warm")
        edit(SHAPE, "val spin: Boolean = true)", "val spin: Boolean = true, val tilt: Float = 0f)")
        rc, out, dt = gradle("hotReload", "--no-warm")
        show(out, KEEP)
        check("#18: --no-warm prints the reasons with advice and 'not restarted (--no-warm)', sends nothing",
              rc != 0 and re.search(r"^compat: \d+ ABI changes?, cannot hot-patch; not restarted \(--no-warm\)$", out, re.M) and "    -> " in out)
except Exception as e:
    check(f"script error: {type(e).__name__}: {e}", False)
finally:
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
    gradle("hotReloadDaemonStop")
    ax_ok, ax_line = ax_keys("restore")
    check("simulator accessibility switch written back (sim-ax-keys.sh restore: read back == value before describe-ui)", ax_ok, ax_line)
failed = [n for n, ok in results if not ok]
say(f"summary: {len(results) - len(failed)}/{len(results)} PASS" + ("" if not failed else f"; FAILED: {failed}"))
sys.exit(1 if failed else 0)
