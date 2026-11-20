#!/usr/bin/env python3
"""0.2.x acceptance on the official KMP wizard template (iOS only, Compose Multiplatform UI).

Starts from the untouched wizard zip, adds the test fixture a user project would have (one expect/actual pair
and kotlinx-datetime from Maven Central), then applies only the steps of docs/TRYOUT.md section 7 and checks:

  package -> run (EH check, STUB-SEL) -> real tap shows the greeting
  commonMain body -> HOT; commonMain use of kotlinx-datetime -> HOT; iosMain actual body -> HOT
  expect/actual signature -> HOT (0.2.4, #28); add an expect/actual fun pair and use it -> HOT; remove it -> HOT;
  add an expect/actual class pair -> WARM (counter-example)
  --signatures (#28): signature changes of top-level functions, a final member and @Composable functions are HOT
  (same PID, new output without taps); a cross-module caller (--multi); forceRecompose off -> WARM (counter-example)
  counter-examples: klibs with edited manifests (ABI 2.7.0, metadata 2.7.0, ABI 1.4.1) stop the build before compiling
  release: hotReloadVerifyRelease passes; the KMP plugin's release/debug task graphs are identical with and without the plugin

Every reload is followed by real taps (hide and show the greeting with "Click me!") and an on-screen check (AXe).
With --multi the template gets two more KMP modules, shared -> core -> base (core: its own expect/actual and a Compose
string resource; base: a plain KMP library shared does not depend on directly), and their changes are checked too.

The only deviation from TRYOUT: the plugin repository may be a local copy of the Pages Maven dir (an unreleased version).

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
ap.add_argument("--plugin-version", default="0.2.1")
ap.add_argument("--multi", action="store_true", help="add the core and base KMP modules (0.2.1)")
ap.add_argument("--force-recompose", action="store_true",
                help="toolchain .6+: static call sites and an existing lambda update without taps; control run with forceRecompose=false")
ap.add_argument("--dialog", action="store_true", help="toolchain .6+: a Dialog opens before and after reloads, an open Dialog updates")
ap.add_argument("--compose", default=None, help="set the template's Compose Multiplatform (and material3) version, e.g. 1.13.0-beta02")
ap.add_argument("--back-handler", action="store_true", help="a BackHandler panel: back events reach the handler, a HOT edit changes it")
ap.add_argument("--signatures", action="store_true",
                help="0.2.4 #28: signature changes are HOT (needs --force-recompose for the counter-example)")
ap.add_argument("--adaptive", action="store_true", help="at the end: MainViewController hosting a content-sized ComposeUIView (Compose 1.13)")
ap.add_argument("--prop", action="append", default=[], help="extra key=value for local.properties (e.g. tools of a local toolchain)")
ap.add_argument("--plist-workaround", action="store_true",
                help="toolchain 2.5.255-hotreload.4 only: add the enforceStrictPlistSanityCheck line (TRYOUT section 7, known limitations)")
a = ap.parse_args()

WORK = os.path.abspath(a.work)
P = os.path.join(WORK, "KmpHr")
SH = os.path.join(P, "shared")
PKG = "src/{}/kotlin/org/example/kmphr"
EXE, BUNDLE = "KmpHr", "org.example.kmphr.hotreload"
BACK = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/BackPanel.kt")
MVC_FILE = os.path.join(P, "shared/src/iosMain/kotlin/org/example/kmphr/MainViewController.kt")
BASE = os.path.join(P, "base/src/commonMain/kotlin/org/example/base/Base.kt")
SIG = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/Sig.kt")
SIG_CALLER = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/SigCaller.kt")
CORE = os.path.join(P, "core/src/commonMain/kotlin/org/example/core/Core.kt")
CORE_IOS = os.path.join(P, "core/src/iosMain/kotlin/org/example/core/Core.ios.kt")
CORE_RES = os.path.join(P, "core/src/commonMain/composeResources/values/strings.xml")
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


def show_greeting(expect, prefix="Compose: "):
    """Real taps: hide the greeting if shown, then show it again (the template computes it in remember {})."""
    if greeting() is not None:
        tap("Click me!")
        wait(lambda ls: not any(l.startswith("Compose: ") for l in ls), timeout=10)
    tap("Click me!")
    ok = wait(lambda ls: any(l.startswith(prefix) and all(e in l for e in expect) for l in ls), timeout=20)
    return ok, next((l for l in labels() if l.startswith(prefix)), None)


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


def reload_only(*extra):
    """`gradlew hotReload` without any tap: (HOT and loaded, output, pid before, pid after)."""
    p0 = pid()
    rc, out, dt = gradle("hotReload", *extra)
    if rc != 0:
        say("\n".join(out.splitlines()[-25:]))
    return rc == 0 and "compat: HOT" in out and "app loaded the patch" in out, out, p0, pid()


def reload(name, expect_kind, screen, *, warm_reason=None, prefix="Compose: "):
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
    shown, g = show_greeting(screen, prefix)
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
say("# plugin repository " + ("a local Maven directory" if a.plugin_repo.startswith("file:") else a.plugin_repo))
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
    if a.compose:
        toml_text = open(toml).read()
        toml_text = re.sub(r'(?m)^composeMultiplatform = ".*"$', f'composeMultiplatform = "{a.compose}"', toml_text)
        toml_text = re.sub(r'(?m)^material3 = ".*"$', f'material3 = "{a.compose}"', toml_text)
        open(toml, "w").write(toml_text)
        say(f"   Compose Multiplatform (and material3) {a.compose}")
    if a.back_handler:
        write(BACK, """package org.example.kmphr

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

fun backTag(): String = "bk-1"

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BackPanel() {
    var backs by remember { mutableStateOf(0) }
    BackHandler(enabled = true) { backs += 1 }
    // A back event the way the iOS edge swipe sends it: an input -> the window's dispatcher -> the handler.
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val input = remember { DirectNavigationEventInput() }
    DisposableEffect(dispatcher) {
        dispatcher?.addInput(input)
        onDispose { dispatcher?.removeInput(input) }
    }
    Text("Backs: $backs | ${backTag()}")
    Button(onClick = { input.backCompleted() }) { Text("SimBack") }
}
""")
        edit(APP, '                Text("Click me!")\n            }\n', '                Text("Click me!")\n            }\n            BackPanel()\n')
        say("   fixture: a BackHandler panel (Backs counter, SimBack button)")
    edit(APP, 'Text("Compose: $greeting")', 'Text("Compose: $greeting | ${platformTag()} | ${dateLine()}")')
    if a.signatures:
        write(SIG, """package org.example.kmphr

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

fun sigLabel(): String = "sl-1"

class Describer {
    fun describe(): String = "ds-1"
}

@Composable
fun SigBadge(text: String) {
    var taps by remember { mutableStateOf(0) }
    Text("Badge: $text | taps=$taps")
    Button(onClick = { taps += 1 }) { Text("BadgeTap") }
}
""")
        write(SIG_CALLER, 'package org.example.kmphr\n\nfun sigLine(): String = "${sigLabel()}/${Describer().describe()}"\n')
        edit(APP, '                Text("Click me!")\n            }\n',
             '                Text("Click me!")\n            }\n            Text("Sig: ${sigLine()} | ${sigLabel()}")\n            SigBadge("b-1")\n')
        say("   fixture (--signatures): Sig.kt (sigLabel, Describer.describe, @Composable SigBadge with a remember counter), "
            "SigCaller.kt (a caller in another file)")
    if a.force_recompose or a.dialog:
        edit(APP, "var showContent by remember { mutableStateOf(false) }",
             "var showContent by remember { mutableStateOf(false) }\n        var dialogOpen by remember { mutableStateOf(false) }")
        extra = '                Text("Click me!")\n            }\n            Text("Static: st-1")\n'
        if a.dialog:
            extra += ('            Button(onClick = { dialogOpen = true }) { Text("Open dialog") }\n'
                      '            if (dialogOpen) {\n'
                      '                androidx.compose.ui.window.Dialog(onDismissRequest = { dialogOpen = false }) {\n'
                      '                    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {\n'
                      '                        Text("Dialog: dlg-1 | ${platformTag()}")\n'
                      '                        Button(onClick = { dialogOpen = false }) { Text("Close dialog") }\n'
                      '                    }\n'
                      '                }\n'
                      '            }\n')
        edit(APP, '                Text("Click me!")\n            }\n', extra)
        say("   fixture: a static Text in App()" + (" and a Dialog (Open dialog / Close dialog)" if a.dialog else ""))
    if a.multi:
        say("   --multi: modules core (expect/actual, Compose string resource) and base; shared -> core -> base")
        edit(os.path.join(P, "settings.gradle.kts"), 'include(":shared")', 'include(":shared")\ninclude(":core")\ninclude(":base")')
        edit(build, "implementation(libs.compose.runtime)", "implementation(libs.compose.runtime)\n            implementation(project(\":core\"))")
        write(os.path.join(P, "base/build.gradle.kts"), "plugins {\n    alias(libs.plugins.kotlinMultiplatform)\n}\n\nkotlin {\n    iosArm64()\n    iosSimulatorArm64()\n}\n")
        write(BASE, 'package org.example.base\n\nfun baseLine(): String = "base-1"\n')
        write(os.path.join(P, "core/build.gradle.kts"), """plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":base"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
        }
    }
}
""")
        write(CORE, """package org.example.core

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kmphr.core.generated.resources.Res
import kmphr.core.generated.resources.core_title
import org.example.base.baseLine
import org.jetbrains.compose.resources.stringResource

expect fun coreTag(): String

fun coreLine(): String = "core-1 " + baseLine()

@Composable
fun CoreBadge() {
    Text("Core: ${stringResource(Res.string.core_title)} | ${coreTag()} | ${coreLine()}")
}
""")
        write(CORE_IOS, 'package org.example.core\n\nactual fun coreTag(): String = "core-ios-1"\n')
        write(CORE_RES, '<resources>\n    <string name="core_title">Core title v1</string>\n</resources>\n')
        edit(APP, 'Text("Compose: $greeting | ${platformTag()} | ${dateLine()}")',
             'Text("Compose: $greeting | ${platformTag()} | ${dateLine()}")\n                    org.example.core.CoreBadge()')

    # ---------------------------------------------------------------- 2. TRYOUT section 7, steps 1-4
    say("## 2. TRYOUT section 7: plugin repository, plugin line, local.properties, .gitignore")
    edit(os.path.join(P, "settings.gradle.kts"), "pluginManagement {\n    repositories {\n",
         f'pluginManagement {{\n    repositories {{\n        maven {{ url = uri("{a.plugin_repo}") }}\n')
    edit(build, "plugins {\n", f'plugins {{\n    id("com.solufee.cmp.hotreload") version "{a.plugin_version}"\n')
    write(os.path.join(P, "local.properties"),
          f"cmp.hotreload.toolchainDir={a.toolchain}\ncmp.hotreload.simulatorUdid={a.udid}\ncmp.hotreload.port={a.port}\n" +
          "".join(f"{p}\n" for p in a.prop))
    if a.plist_workaround:
        say("   (toolchain .4 workaround: enforceStrictPlistSanityCheck = false in MainViewController.kt)")
        edit(path("iosMain", "MainViewController.kt"), "ComposeUIViewController {", "ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) {")
    else:
        say("   MainViewController.kt unchanged")
    SRC_DIRS = [os.path.join(SH, "src")] + ([os.path.join(P, "core/src"), os.path.join(P, "base/src")] if a.multi else [])
    with open(os.path.join(P, ".gitignore"), "a") as g:
        g.write("\nlocal.properties\nshared/.hr/\nshared/launch.log\n")

    # ---------------------------------------------------------------- 2b. release baseline (before any hot reload)
    say("## 2b. release baseline: hotReloadVerifyRelease -Pcmp.hotreload.releaseBaseline=true, sources snapshot")
    for d in SRC_DIRS:
        shutil.copytree(d, os.path.join(WORK, "src-baseline", os.path.relpath(d, P)))
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
    if a.multi:
        ok, g = show_greeting(["Core title v1", "core-ios-1", "core-1 base-1"], "Core: ")
        check("core module shows its string resource, actual and base's function", ok, repr(g))

    if a.force_recompose:
        say("## 3f. forced recomposition (toolchain .6): new code on screen without any tap")
        edit(APP, '"Static: st-1"', '"Static: st-2"')
        ok, out, p0, p1 = reload_only()
        seen = wait(lambda ls: "Static: st-2" in ls, timeout=15)
        check("static call site in App() updates without taps -> HOT", ok and seen and p0 == p1, f"pid {p0}->{p1} labels={[l for l in labels() if l.startswith('Static')]}")
        edit(APP, 'Text("Click me!")', 'Text("Click me now!")')
        ok, out, p0, p1 = reload_only()
        seen = wait(lambda ls: "Click me now!" in ls, timeout=15)
        check("existing lambda (Button content) updates without taps -> HOT", ok and seen and p0 == p1, f"pid {p0}->{p1}")
        edit(APP, 'Text("Click me now!")', 'Text("Click me!")')
        ok, out, p0, p1 = reload_only()
        seen = wait(lambda ls: "Click me!" in ls, timeout=15)
        check("the lambda changed back updates without taps -> HOT", ok and seen and p0 == p1, f"pid {p0}->{p1}")
        log = open(LOG, errors="replace").read()
        n = len(re.findall(r"HR forceRecompose", log))
        check("the app logged its forced recompositions", n >= 3, f"{n} 'HR forceRecompose' lines; " + "; ".join(re.findall(r"HR forceRecompose[^\n]*", log)[-1:]))
    if a.dialog:
        say("## 3d. Dialog (toolchain .6): opens before and after a reload with the new content; an open Dialog updates")
        tap("Open dialog")
        ok1 = wait(lambda ls: any(l.startswith("Dialog: dlg-1") for l in ls), timeout=15)
        tap("Close dialog")
        ok2 = wait(lambda ls: not any(l.startswith("Dialog: ") for l in ls), timeout=10)
        check("Dialog opens and closes before any reload", ok1 and ok2)
        edit(APP, '"Dialog: dlg-1 | ', '"Dialog: dlg-2 | ')
        ok, out, p0, p1 = reload_only()
        tap("Open dialog")
        ok1 = wait(lambda ls: any(l.startswith("Dialog: dlg-2") for l in ls), timeout=15)
        g = next((l for l in labels() if l.startswith("Dialog: ")), None)
        tap("Close dialog")
        ok2 = wait(lambda ls: not any(l.startswith("Dialog: ") for l in ls), timeout=10)
        check("after a HOT reload the Dialog opens with the new content", ok and ok1 and ok2 and p0 == p1, f"{g!r} pid {p0}->{p1}")
        tap("Open dialog")
        wait(lambda ls: any(l.startswith("Dialog: dlg-2") for l in ls), timeout=15)
        edit(APP, '"Dialog: dlg-2 | ', '"Dialog: dlg-3 | ')
        ok, out, p0, p1 = reload_only()
        ok1 = wait(lambda ls: any(l.startswith("Dialog: dlg-3") for l in ls), timeout=15)
        g = next((l for l in labels() if l.startswith("Dialog: ")), None)
        tap("Close dialog")
        ok2 = wait(lambda ls: not any(l.startswith("Dialog: ") for l in ls), timeout=10)
        check("an open Dialog shows the new content after a HOT reload, then closes", ok and ok1 and ok2 and p0 == p1, f"{g!r} pid {p0}->{p1}")
        log = open(LOG, errors="replace").read()
        crash = re.search(r"Uncaught|IrLinkageError|EXC_|SIGSEGV", log)
        check("no crash or IrLinkageError with Dialog", not crash, crash.group(0) if crash else "")

    if a.back_handler:
        say("## 3b. BackHandler (Compose 1.13 / toolchain .7)")
        tap("SimBack")
        ok1 = wait(lambda ls: "Backs: 1 | bk-1" in ls, timeout=15)
        check("a back event reaches the BackHandler", ok1, str([l for l in labels() if l.startswith("Backs")]))
        edit(BACK, '"bk-1"', '"bk-2"')
        ok, out, p0, p1 = reload_only()
        ok2 = wait(lambda ls: "Backs: 1 | bk-2" in ls, timeout=15)
        check("HOT edit next to the handler: shown without taps, the counter kept", ok and ok2 and p0 == p1, str([l for l in labels() if l.startswith("Backs")]))
        edit(BACK, "BackHandler(enabled = true) { backs += 1 }", "BackHandler(enabled = true) { backs += 10 }")
        ok, out, p0, p1 = reload_only()
        tap("SimBack")
        ok3 = wait(lambda ls: "Backs: 11 | bk-2" in ls, timeout=15)
        check("HOT edit of the handler itself: the next back event runs the new code", ok and ok3 and p0 == p1, str([l for l in labels() if l.startswith("Backs")]))
        log = open(LOG, errors="replace").read()
        crash = re.search(r"Uncaught|IrLinkageError|EXC_|SIGSEGV", log)
        check("no crash or IrLinkageError with BackHandler", not crash, crash.group(0) if crash else "")

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
    # 0.2.4 (#28): an expect/actual function is a plain function after actualization; its callers are in the patch.
    reload("expect/actual signature (platformTag(n: Int = 0))", "HOT", ["tag-ios-3", "Hello again", "2026-11-21"])
    edit(EXTRAS, "fun dateLine()", "expect fun flavor(): String\n\nfun dateLine()")
    edit(EXTRAS_IOS, "actual fun platformTag", 'actual fun flavor(): String = "sim"\n\nactual fun platformTag')
    edit(EXTRAS_IOS, '"tag-ios-3"', '"tag-ios-4 " + flavor()')
    reload("add an expect/actual fun pair (flavor) and use it", "HOT", ["tag-ios-4 sim"])
    edit(EXTRAS, "expect fun flavor(): String\n\n", "")
    edit(EXTRAS_IOS, 'actual fun flavor(): String = "sim"\n\n', "")
    edit(EXTRAS_IOS, '"tag-ios-4 " + flavor()', '"tag-ios-5"')
    reload("remove the expect/actual fun pair (flavor)", "HOT", ["tag-ios-5"])
    edit(EXTRAS, "fun dateLine()", "expect class Clock() {\n    fun now(): Long\n}\n\nfun dateLine()")
    edit(EXTRAS_IOS, "actual fun platformTag", "actual class Clock actual constructor() {\n    actual fun now(): Long = 7L\n}\n\nactual fun platformTag")
    reload("add an expect/actual class pair (counter-example)", "WARM", ["tag-ios-5"], warm_reason=r"^WARM expect-actual Clock: expect class added")
    edit(GREET, '"Hello again, ', '"Hello once more, ')
    reload("commonMain body after the pair changes", "HOT", ["Hello once more, iOS", "tag-ios-5"])

    if a.multi:
        say("## 4m. library modules: core (direct) and base (only through core)")
        edit(BASE, '"base-1"', '"base-2"')
        reload("base body (library reached only through core)", "HOT", ["core-1 base-2"], prefix="Core: ")
        edit(CORE, '"core-1 "', '"core-2 "')
        reload("core commonMain body", "HOT", ["core-2 base-2"], prefix="Core: ")
        edit(CORE_IOS, '"core-ios-1"', '"core-ios-2"')
        reload("core iosMain actual body", "HOT", ["core-ios-2"], prefix="Core: ")
        edit(CORE, "expect fun coreTag(): String", "expect fun coreTag(n: Int = 0): String")
        edit(CORE_IOS, 'actual fun coreTag(): String = "core-ios-2"', 'actual fun coreTag(n: Int): String = "core-ios-3"')
        reload("core expect/actual signature", "HOT", ["core-ios-3", "core-2 base-2"], prefix="Core: ")
        edit(CORE, "fun coreLine()", "expect fun coreFlavor(): String\n\nfun coreLine()")
        edit(CORE_IOS, "actual fun coreTag", 'actual fun coreFlavor(): String = "sim"\n\nactual fun coreTag')
        edit(CORE_IOS, '"core-ios-3"', '"core-ios-4 " + coreFlavor()')
        reload("add an expect/actual fun pair in core and use it", "HOT", ["core-ios-4 sim"], prefix="Core: ")
        edit(CORE, "expect fun coreFlavor(): String\n\n", "")
        edit(CORE_IOS, 'actual fun coreFlavor(): String = "sim"\n\n', "")
        edit(CORE_IOS, '"core-ios-4 " + coreFlavor()', '"core-ios-5"')
        reload("remove the expect/actual fun pair in core", "HOT", ["core-ios-5"], prefix="Core: ")
        edit(BASE, '"base-2"', '"base-3"')
        reload("base body after the pair changes", "HOT", ["core-2 base-3"], prefix="Core: ")
        say("## 4r. a Compose string resource of core: one hotReload -> warm restart (resources are copied at package time)")
        edit(CORE_RES, "Core title v1", "Core title v2")
        reload("core string resource (composeResources)", "WARM", ["Core title v2", "core-ios-5", "core-2 base-3"],
               warm_reason=r"^WARM compose-resources: 1 file\(s\) changed", prefix="Core: ")

    if a.signatures:
        say("## 4s. signature changes are HOT (#28): every caller is recompiled into the patch")

        def sig_reload(name, want, *, note=None, timeout=20):
            ok, out, p0, p1 = reload_only()
            seen = wait(lambda ls: any(want in l for l in ls), timeout=timeout)
            got = [l for l in labels() if l.startswith(("Sig: ", "Badge: "))]
            notes = grep(out, r"^compat: note: ")
            okn = (note is None and not notes) or (note is not None and any(note in l for l in notes))
            check(f"{name} -> HOT, same PID, new output without taps", ok and seen and p0 == p1 and okn,
                  f"pid {p0}->{p1} {got} notes={notes}")
            return ok and seen

        if not wait(lambda ls: "Sig: sl-1/ds-1 | sl-1" in ls, timeout=10):
            check("fixture: Sig line shown", False, str([l for l in labels() if l.startswith("Sig")]))
        edit(SIG, 'fun sigLabel(): String = "sl-1"', 'fun sigLabel(suffix: String = "!"): String = "sl-2$suffix"')
        sig_reload("top-level: parameter with a default added (callers in two files)", "Sig: sl-2!/ds-1 | sl-2!")
        edit(SIG, 'fun sigLabel(suffix: String = "!"): String = "sl-2$suffix"', 'fun sigLabel(): String = "sl-3"')
        sig_reload("top-level: parameter removed", "Sig: sl-3/ds-1 | sl-3")
        edit(SIG, 'fun sigLabel(): String = "sl-3"', 'fun sigLabel(): List<String> = listOf("sl-4")')
        sig_reload("top-level: return type changed (String -> List<String>)", "Sig: [sl-4]/ds-1 | [sl-4]")
        edit(SIG, '    fun describe(): String = "ds-1"', '    fun describe(prefix: String = "P"): String = "$prefix-ds-2"')
        sig_reload("final member: parameter added", "Sig: [sl-4]/P-ds-2 | [sl-4]")
        tap("BadgeTap")
        tap("BadgeTap")
        before = wait(lambda ls: "Badge: b-1 | taps=2" in ls, timeout=10)
        greet_before = greeting()
        edit(SIG, "fun SigBadge(text: String) {", 'fun SigBadge(text: String, rim: String = "R") {')
        edit(SIG, 'Text("Badge: $text | taps=$taps")', 'Text("Badge: $text$rim | taps=$taps")')
        sig_reload("@Composable: parameter added (forceRecompose on), remember-reset note", "Badge: b-1R | taps=",
                   note="SigBadge changed its signature")
        after = next((l for l in labels() if l.startswith("Badge: ")), None)
        check("the composable's own remember state starts over, its caller's state is kept (recorded)",
              before and after == "Badge: b-1R | taps=0" and greeting() == greet_before,
              f"before taps=2: {before}; after: {after!r}; greeting kept: {greeting() == greet_before}")
        tap("BadgeTap")
        check("the changed composable keeps working (a tap counts)", wait(lambda ls: "Badge: b-1R | taps=1" in ls, timeout=10))
        if a.multi:
            say("## 4s-m. cross-module callers")
            edit(BASE, 'fun baseLine(): String = "base-3"', 'fun baseLine(n: Int = 4): String = "base-$n"')
            reload("base: parameter added, caller in core (another module)", "HOT", ["core-2 base-4"], prefix="Core: ")
            edit(CORE, "fun CoreBadge() {", 'fun CoreBadge(suffix: String = "!") {')
            edit(CORE, '${coreLine()}")', '${coreLine()}$suffix")')
            p0 = pid()
            rc, out, dt = gradle("hotReload")
            notes = grep(out, r"^compat: note: ")
            seen = wait(lambda ls: any(l.startswith("Core: ") and l.endswith("base-4!") for l in ls), timeout=20)
            check("core @Composable: parameter added, caller in shared (another module) -> HOT with a note",
                  rc == 0 and "compat: HOT" in out and seen and pid() == p0 and any("CoreBadge" in l for l in notes),
                  f"{dt:.1f}s notes={notes} {[l for l in labels() if l.startswith('Core: ')]}")
        log = open(LOG, errors="replace").read()
        crash = re.search(r"Uncaught|IrLinkageError|EXC_|SIGSEGV|SIGBUS", log)
        check("no crash or IrLinkageError after the signature changes", not crash, crash.group(0) if crash else "")

    if a.force_recompose:
        say("## 4f. control: relaunched with -Pcmp.hotreload.forceRecompose=false, the static call site stays stale")
        rc, out, dt = gradle("hotReloadRun", "-Pcmp.hotreload.forceRecompose=false")
        startup = open(LOG, errors="replace").read()
        edit(APP, '"Static: st-2"', '"Static: st-off"')
        ok, out, p0, p1 = reload_only("-Pcmp.hotreload.forceRecompose=false")
        stale = not wait(lambda ls: "Static: st-off" in ls, timeout=8)
        log = open(LOG, errors="replace").read()
        forced = re.findall(r"HR forceRecompose recomposers=", log)
        check("forceRecompose=false: app starts with it off, the reload is HOT, the static call site stays stale",
              rc == 0 and ok and stale and p0 == p1 and "HR forceRecompose: off" in startup and not forced,
              f"startup line {re.findall(r'HR forceRecompose: [^\n]*', startup)}, forced recompositions {len(forced)}, labels={[l for l in labels() if l.startswith('Static')]}")
        if a.signatures:
            # #28 counter-example: with forceRecompose off the restart lambda keeps the old parameters -> WARM.
            edit(SIG, 'fun SigBadge(text: String, rim: String = "R") {', 'fun SigBadge(text: String, rim: String = "R", tail: String = "") {')
            rc, out, dt = gradle("hotReload", "--no-warm", "-Pcmp.hotreload.forceRecompose=false")
            warm = grep(out, r"^WARM signature SigBadge")
            check("forceRecompose off: a @Composable signature change is WARM (not sent)",
                  rc != 0 and "compat: WARM" in out and any("forceRecompose off" in l for l in warm) and pid() == p0,
                  f"{warm}")
            edit(SIG, 'fun SigBadge(text: String, rim: String = "R", tail: String = "") {', 'fun SigBadge(text: String, rim: String = "R") {')
        # hotReloadRun reinstalls the last packaged app (patches are not in it): package first, then run with the default.
        rc1, _, _ = gradle("hotReloadPackage")
        rc2, _, _ = gradle("hotReloadRun")
        startup = open(LOG, errors="replace").read()
        edit(APP, '"Static: st-off"', '"Static: st-on"')
        ok, out, p0, p1 = reload_only()
        seen = wait(lambda ls: "Static: st-on" in ls, timeout=15)
        check("back to the default (forceRecompose on): the next change shows without taps",
              rc1 == 0 and rc2 == 0 and ok and seen and p0 == p1 and "HR forceRecompose: on" in startup,
              f"labels={[l for l in labels() if l.startswith('Static')]}")

    if a.adaptive:
        say("## 4a. content-adaptive ComposeUIView (Compose 1.13) inside the app's MainViewController")
        write(MVC_FILE, """@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package org.example.kmphr

import androidx.compose.material3.Text
import androidx.compose.ui.window.ComposeUIView
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIColor
import platform.UIKit.UIViewController

fun adaptiveTag(): String = "ad-1"

// The app's own view controller hosts a ComposeUIView (Compose 1.13) instead of being a ComposeUIViewController.
// Content inside a ComposeUIView is not exposed to accessibility, so the text is also printed to the log.
fun MainViewController(): UIViewController = UIViewController().apply {
    view.backgroundColor = UIColor.whiteColor
    val compose = ComposeUIView {
        val text = "Adaptive: ${adaptiveTag()}"
        println("ADAPTIVE " + text)
        Text(text)
    }
    compose.translatesAutoresizingMaskIntoConstraints = false
    view.addSubview(compose)
    NSLayoutConstraint.activateConstraints(listOf(
        compose.centerXAnchor.constraintEqualToAnchor(view.centerXAnchor),
        compose.centerYAnchor.constraintEqualToAnchor(view.centerYAnchor),
        compose.widthAnchor.constraintEqualToConstant(240.0),
        compose.heightAnchor.constraintEqualToConstant(60.0),
    ))
}
""")
        rc, out, dt = gradle("hotReloadPackage")
        check("package with a ComposeUIView entry (generated entry + Info.plist unchanged)", rc == 0, f"{dt:.0f}s " + "; ".join(l for l in out.splitlines() if "rror" in l)[:200])
        rc, out, dt = gradle("hotReloadRun")
        t0 = time.time()
        while time.time() - t0 < 20 and "ADAPTIVE Adaptive: ad-1" not in open(LOG, errors="replace").read():
            time.sleep(0.5)
        view = next((n.get("frame") for n in walk(tree()) if (n.get("frame") or {}).get("width") == 240 and (n.get("frame") or {}).get("height") == 60), None)
        shot = os.path.join(WORK, "adaptive-ad-1.png")
        subprocess.run(["xcrun", "simctl", "io", a.udid, "screenshot", shot], capture_output=True)
        check("run: the ComposeUIView renders its content at its own size inside the app's view controller",
              rc == 0 and view is not None and "ADAPTIVE Adaptive: ad-1" in open(LOG, errors="replace").read(), f"view frame {view}; screenshot {os.path.basename(shot)}")
        launch_checks("ComposeUIView entry")
        edit(MVC_FILE, '"ad-1"', '"ad-2"')
        ok, out, p0, p1 = reload_only()
        t0 = time.time()
        while time.time() - t0 < 15 and "ADAPTIVE Adaptive: ad-2" not in open(LOG, errors="replace").read():
            time.sleep(0.5)
        subprocess.run(["xcrun", "simctl", "io", a.udid, "screenshot", os.path.join(WORK, "adaptive-ad-2.png")], capture_output=True)
        check("HOT edit inside the ComposeUIView: recomposed with the new code without taps",
              ok and p0 == p1 and "ADAPTIVE Adaptive: ad-2" in open(LOG, errors="replace").read())

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
    for d in SRC_DIRS:
        shutil.rmtree(d)
        shutil.copytree(os.path.join(WORK, "src-baseline", os.path.relpath(d, P)), d)
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
    open(build, "w").write(build_text.replace(f'    id("com.solufee.cmp.hotreload") version "{a.plugin_version}"\n', ""))
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
