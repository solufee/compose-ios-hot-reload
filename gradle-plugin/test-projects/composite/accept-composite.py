#!/usr/bin/env python3
"""0.2.2 acceptance for composite builds (includeBuild), on the fixture in this directory.

The fixture: root build CompositeApp (:app with the plugin, :feature through a convention plugin from the included
build build-logic) and the included build lib-build (:util, :core with expect/actual and a Compose string resource,
:demo with the plugin). :app uses org.example.lib:core:1.0, substituted from lib-build.

  A  includedModule("lib-build", ":util"/":core") declared (lib-build modules compiled from source):
     release baseline, package, run (EH, SELREF), screen; HOT in :app, :feature (body and actual), :core (body and
     actual) and :util (reached only through :core), all on screen without taps; WARM for core's expect signature,
     adding/removing an expect/actual pair in core and core's string resource; a Dialog updates while open; release
     check by __text digest (daemons still running).
  B  without the declarations: lib-build's own build compiles core/util to klibs, the app links them (the plugin
     prints how to hot reload them), core's resources are bundled, a change in core warm-restarts the app.
  C  the plugin applied inside the included build (:lib-build:demo), run from the root build: it uses lib-build's own
     local.properties (another port), build/hotreload under lib-build/demo, and hot reloads demo and core.
  and: ~/.konan/dependencies is not touched; libKaldo writes no SELREF debug log.

Usage: accept-composite.py --zip WIZARD.zip --work DIR --plugin-repo URL --toolchain DIR --udid U [--plugin-version 0.2.2]
       (the wizard zip provides the Gradle wrapper). Stops its apps (by bundle id) and the compile daemons at the end.
"""
import argparse
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
ap.add_argument("--plugin-version", default="0.2.2")
ap.add_argument("--toolchain", required=True, help="<TOOLCHAIN>/kotlin-native")
ap.add_argument("--udid", required=True)
ap.add_argument("--port", default="5568")
ap.add_argument("--demo-port", default="5569")
a = ap.parse_args()

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "../../.."))
WORK = os.path.abspath(a.work)
P = os.path.join(WORK, "CompositeApp")
HOME = os.path.expanduser("~")
GAP = "/tmp/gap-selrefs.log"
KONAN_DEPS = os.path.join(HOME, ".konan", "dependencies")
results = []
OUTPUTS = []   # every Gradle output, for the configuration cache check at the end

APP = dict(exe="CompositeApp", bundle="org.example.app.hotreload", log=os.path.join(P, "app/launch.log"), tasks=":app")
DEMO = dict(exe="libbuild", bundle="org.example.demo.hotreload", log=os.path.join(P, "lib-build/demo/launch.log"), tasks=":lib-build:demo")
F = {
    "app": "app/src/commonMain/kotlin/org/example/app/App.kt",
    "feature": "feature/src/commonMain/kotlin/org/example/feature/Feature.kt",
    "feature_ios": "feature/src/iosMain/kotlin/org/example/feature/Feature.ios.kt",
    "core": "lib-build/core/src/commonMain/kotlin/org/example/core/Core.kt",
    "core_ios": "lib-build/core/src/iosMain/kotlin/org/example/core/Core.ios.kt",
    "core_res": "lib-build/core/src/commonMain/composeResources/values/strings.xml",
    "util": "lib-build/util/src/commonMain/kotlin/org/example/util/Util.kt",
    "shared": "lib-build/util/src/iosSharedMain/kotlin/org/example/util/Shared.kt",
    "demo": "lib-build/demo/src/commonMain/kotlin/org/example/demo/Demo.kt",
    "app_build": "app/build.gradle.kts",
}
SRC_DIRS = ["app/src", "feature/src", "lib-build/core/src", "lib-build/util/src", "lib-build/demo/src"]


def clean(text):
    return text.replace(WORK, "<WORK>").replace(REPO, "<REPO>").replace(HOME, "<HOME>")


def say(line=""):
    print(clean(line), flush=True)


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    say(f"{'PASS' if ok else 'FAIL'} {name}" + (f" | {detail}" if detail else ""))
    return ok


def f(key):
    return os.path.join(P, F[key])


def edit(key, old, new):
    s = open(f(key)).read()
    if old not in s:
        raise KeyError(f"{old!r} not in {F[key]}")
    open(f(key), "w").write(s.replace(old, new, 1))


def gradle(*args, timeout=1800):
    t0 = time.time()
    r = subprocess.run([os.path.join(P, "gradlew"), "-p", P, *args, "--console=plain"], cwd=P, capture_output=True, text=True, timeout=timeout)
    OUTPUTS.append(r.stdout + r.stderr)
    return r.returncode, r.stdout + r.stderr, time.time() - t0


def linked():
    """The klibs the app was last packaged with: (kind, path) from build/hotreload/linked-klibs.txt."""
    path = os.path.join(P, "app/build/hotreload/linked-klibs.txt")
    return [tuple(l.split("  ", 1)) for l in open(path).read().splitlines() if l.strip()] if os.path.exists(path) else []


def mentions(entries, name):
    """Entries that are this module: our module klib (lib-build-<name>) or a klib under lib-build/<name>/ or named <name>.klib."""
    return [e for e in entries if f"lib-build-{name}" in e[1] or f"/lib-build/{name}/" in e[1] or os.path.basename(e[1]) == f"{name}.klib"]


def grep(out, pattern):
    return [l for l in out.splitlines() if re.search(pattern, l)]


# ---------------------------------------------------------------- simulator (AXe)
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


def wait(pred, timeout=20):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if pred(labels()):
            return True
        time.sleep(0.3)
    return False


def has(*parts, timeout=20):
    """Wait until one label contains all parts."""
    return wait(lambda ls: any(all(p in l for p in parts) for l in ls), timeout)


def label(prefix):
    return [l for l in labels() if l.startswith(prefix)]


def tap(text):
    t = tree()
    app = next(n for n in walk(t) if n.get("type") == "Application")
    af = app["frame"]
    b = [n for n in walk(t) if n.get("type") == "Button" and n.get("AXLabel") == text][0]["frame"]
    sw, sh = 402.0, 874.0
    s = min(sw / af["width"], sh / af["height"])
    ox, oy = (sw - af["width"] * s) / 2, (sh - af["height"] * s) / 2
    x, y = round(ox + (b["x"] + b["width"] / 2) * s, 1), round(oy + (b["y"] + b["height"] / 2) * s, 1)
    subprocess.run(["axe", "tap", "-x", str(x), "-y", str(y), "--udid", a.udid], capture_output=True, check=True)


def pid(app):
    o = subprocess.run(["pgrep", "-f", f"Devices/{a.udid}/.*/{app['exe']}.app/{app['exe']}"], capture_output=True, text=True).stdout.split()
    return o[0] if o else None


def launch_checks(app, tag):
    log = open(app["log"], errors="replace").read() if os.path.exists(app["log"]) else ""
    eh = re.findall(r"EH check: (\d+) FDE.*?(\d+) pc-out-of-range", log)
    sel = re.findall(r"STUB-SEL check: (\d+) stubs, (\d+) mismatch", log)
    crash = re.search(r"Uncaught|EXC_|Terminating app|SIGSEGV|SIGBUS|MissingResourceException|IrLinkageError", log)
    check(f"{tag}: EH check FDE > 0, 0 pc-out-of-range; SELREF 0 mismatch; no crash",
          eh and int(eh[0][0]) > 0 and eh[0][1] == "0" and sel and all(m == "0" for _, m in sel) and not crash,
          f"EH {eh[:1]} SEL {sel[:1]} crash={crash.group(0) if crash else 'none'}")


def reload(app, name, kind, *expect, reason=None):
    """`gradlew <app>:hotReload`; the new text must show without any tap (forced recomposition, toolchain .6)."""
    p0 = pid(app)
    rc, out, dt = gradle(f"{app['tasks']}:hotReload")
    got = "HOT" if "compat: HOT" in out else ("WARM" if "compat: WARM" in out else "?")
    warm = grep(out, r"^WARM ")
    ok = rc == 0 and got == kind
    if kind == "HOT":
        ok = ok and "app loaded the patch" in out
    else:
        ok = ok and any(l.startswith("WARM OK") for l in warm) and (reason is None or any(re.search(reason, l) for l in warm))
    seen = has(*expect, timeout=25)
    p1 = pid(app)
    ok = ok and seen and ((p1 == p0) if kind == "HOT" else (p1 is not None and p1 != p0))
    changed = "; ".join(grep(out, r"^changed objects"))
    check(f"{name} -> {kind}", ok, f"{dt:.1f}s exit={rc} compat={got} pid {p0}->{p1} {changed} {warm[:1]} screen has {expect}: {seen}")
    if rc != 0:
        say("\n".join(out.splitlines()[-20:]))
    if kind == "WARM":
        launch_checks(app, f"{name} (after warm restart)")
    return ok


def stop(app):
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, app["bundle"]], capture_output=True)
    gradle(f"{app['tasks']}:hotReloadDaemonStop")


def deps_listing():
    if not os.path.isdir(KONAN_DEPS):
        return "absent"
    return "\n".join(sorted(f"{e}:{int(os.path.getmtime(os.path.join(KONAN_DEPS, e)))}" for e in os.listdir(KONAN_DEPS)))


# ---------------------------------------------------------------- 0. fixture copy
say(f"# composite build acceptance, {time.strftime('%F %T')}, simulator {a.udid}, ports {a.port} (app) / {a.demo_port} (lib-build demo)")
say(f"# plugin {a.plugin_version} from {a.plugin_repo}")
if os.path.exists(WORK):
    shutil.rmtree(WORK)
shutil.copytree(HERE, P, ignore=shutil.ignore_patterns("accept-composite.py", "evidence", "build", ".gradle", ".kotlin", ".hr", "launch.log", "local.properties"))
with zipfile.ZipFile(a.zip) as z:
    z.extract("KmpHr/gradlew", WORK)
    for n in z.namelist():
        if n.startswith("KmpHr/gradle/wrapper/"):
            z.extract(n, WORK)
shutil.copy(os.path.join(WORK, "KmpHr/gradlew"), os.path.join(P, "gradlew"))
shutil.copytree(os.path.join(WORK, "KmpHr/gradle/wrapper"), os.path.join(P, "gradle/wrapper"))
os.chmod(os.path.join(P, "gradlew"), 0o755)
for s in ["settings.gradle.kts", "lib-build/settings.gradle.kts"]:
    t = open(os.path.join(P, s)).read().replace("// HR_PLUGIN_REPOSITORY", f'maven {{ url = uri("{a.plugin_repo}") }}')
    open(os.path.join(P, s), "w").write(t)
for b in ["app/build.gradle.kts", "lib-build/demo/build.gradle.kts"]:
    t = open(os.path.join(P, b)).read().replace("HR_PLUGIN_VERSION", a.plugin_version)
    open(os.path.join(P, b), "w").write(t)
props = f"cmp.hotreload.toolchainDir={a.toolchain}\ncmp.hotreload.simulatorUdid={a.udid}\n"
open(os.path.join(P, "local.properties"), "w").write(props + f"cmp.hotreload.port={a.port}\n")
open(os.path.join(P, "lib-build/local.properties"), "w").write(props + f"cmp.hotreload.port={a.demo_port}\n")
deps_before = deps_listing()
gap_before = os.path.getmtime(GAP) if os.path.exists(GAP) else None


def phase_a():
    say("## A. includedModule(\"lib-build\", \":util\"/\":core\"): modules of the included build compiled from source")
    for d in SRC_DIRS:
        shutil.copytree(os.path.join(P, d), os.path.join(WORK, "src-baseline", d))
    rc, out, dt = gradle(":app:hotReloadVerifyRelease", "-Pcmp.hotreload.releaseBaseline=true", timeout=2400)
    check("release baseline recorded (lib-build modules compiled from source)", rc == 0, f"{dt:.0f}s; " + "; ".join(grep(out, r"release baseline recorded")).replace(P, "<P>")[:200])
    rc, out, dt = gradle(":app:hotReloadPackage")
    for l in grep(out, r"dependency klibs|hot packages|compose resources|package timing|BUILD"):
        say("  " + l)
    check("package: lib-build modules are hot packages, core's resources bundled",
          rc == 0 and "org.example.core" in out and "org.example.util" in out and "comes from the included build" not in out, f"{dt:.0f}s")
    if rc != 0:
        say("\n".join(out.splitlines()[-30:]))
        return
    entries = linked()
    core, util = mentions(entries, "core"), mentions(entries, "util")
    check("linked klibs: core and util each exactly once (our module klibs; lib-build's own klibs left out)",
          len(core) == 1 and core[0][0] == "module" and len(util) == 1 and util[0][0] == "module",
          f"core={[e[0] for e in core]} util={[e[0] for e in util]} of {len(entries)} klibs")
    rc, out, dt = gradle(":app:hotReloadPackage")
    check("configuration cache: a second package reuses it (cross-build task references included)",
          rc == 0 and "Reusing configuration cache" in out and "problems were found" not in out, f"{dt:.1f}s")
    rc, out, dt = gradle(":app:hotReloadRun")
    check("run", rc == 0 and "app started" in out, f"{dt:.1f}s pid {pid(APP)}")
    launch_checks(APP, "run")
    check("screen: app, feature (convention plugin), core (included build, string resource, actual) and util",
          has("App: app-1 | feature-1") and has("Feature: feature-1 | feature-ios-1") and has("Core: Core title v1 | core-ios-1 | core-1 util-1"),
          str([l for l in labels() if ":" in l][:4]))

    say("## A1. hot changes in both builds, on screen without taps")
    edit("app", '"app-1"', '"app-2"')
    reload(APP, "root :app body", "HOT", "App: app-2")
    edit("feature", '"feature-1"', '"feature-2"')
    reload(APP, "root :feature body (convention plugin module)", "HOT", "App: app-2 | feature-2", "")
    edit("feature_ios", '"feature-ios-1"', '"feature-ios-2"')
    reload(APP, "root :feature iosMain actual", "HOT", "Feature: feature-2 | feature-ios-2")
    edit("core", '"core-1 "', '"core-2 "')
    reload(APP, "included lib-build:core body", "HOT", "core-2 util-1")
    edit("util", '"util-1"', '"util-2"')
    reload(APP, "included lib-build:util body (used only through core)", "HOT", "core-2 util-2")
    edit("core_ios", '"core-ios-1"', '"core-ios-2"')
    reload(APP, "included lib-build:core iosMain actual", "HOT", "core-ios-2")

    say("## A2. warm changes in the included build")
    edit("core", "expect fun coreTag(): String", "expect fun coreTag(n: Int = 0): String")
    edit("core_ios", 'actual fun coreTag(): String = "core-ios-2"', 'actual fun coreTag(n: Int): String = "core-ios-3"')
    reload(APP, "included core expect/actual signature", "WARM", "core-ios-3", reason=r"coreTag")
    edit("core", "fun coreLine()", "expect fun coreFlavor(): String\n\nfun coreLine()")
    edit("core_ios", "actual fun coreTag", 'actual fun coreFlavor(): String = "sim"\n\nactual fun coreTag')
    reload(APP, "add an expect/actual pair in included core", "WARM", "core-ios-3", reason=r"^WARM expect-actual coreFlavor: expect fun added")
    edit("core", "expect fun coreFlavor(): String\n\n", "")
    edit("core_ios", 'actual fun coreFlavor(): String = "sim"\n\n', "")
    reload(APP, "remove the expect/actual pair in included core", "WARM", "core-ios-3", reason=r"^WARM expect-actual coreFlavor: expect fun removed")
    edit("core_res", "Core title v1", "Core title v2")
    reload(APP, "included core string resource", "WARM", "Core: Core title v2", reason=r"^WARM compose-resources")

    say("## A3. Dialog: open, then a HOT reload of included code updates it")
    tap("Open dialog")
    ok1 = has("Dialog: dlg-1")
    edit("app", '"Dialog: dlg-1"', '"Dialog: dlg-2"')
    edit("util", '"util-2"', '"util-3"')
    p0 = pid(APP)
    rc, out, dt = gradle(":app:hotReload")
    ok2 = rc == 0 and "compat: HOT" in out and has("Dialog: dlg-2") and has("util-3")
    tap("Close dialog")
    ok3 = wait(lambda ls: not any(l.startswith("Dialog: ") for l in ls), 10)
    check("an open Dialog shows the reloaded app and included code (HOT, same pid)", ok1 and ok2 and ok3 and pid(APP) == p0,
          f"{dt:.1f}s labels with util-3: {len([l for l in labels() if 'util-3' in l])}")

    say("## A4. release: sources back to the baseline, hotReloadVerifyRelease (daemons running)")
    for d in SRC_DIRS:
        shutil.rmtree(os.path.join(P, d))
        shutil.copytree(os.path.join(WORK, "src-baseline", d), os.path.join(P, d))
    rc, out, dt = gradle(":app:hotReloadVerifyRelease", timeout=2400)
    for l in grep(out, r"release unchanged|release build changed|BUILD"):
        say("  " + l.replace(P, "<P>"))
    check("hotReloadVerifyRelease (digest) with lib-build modules", rc == 0 and "release unchanged" in out, f"{dt:.0f}s")
    stop(APP)


def phase_b():
    say("## B. without includedModule: lib-build's own build makes the klibs, the app links them")
    s = open(f("app_build")).read()
    i, j = s.index("// HR_INCLUDED_MODULES_BEGIN"), s.index("// HR_INCLUDED_MODULES_END")
    open(f("app_build"), "w").write(s[:i] + s[j:])
    rc, out, dt = gradle(":app:hotReloadPackage")
    hints = grep(out, r"comes from the included build lib-build")
    for l in grep(out, r"dependency klibs|hot packages|compose resources|package timing|BUILD"):
        say("  " + l)
    check("package: core and util linked as lib-build's klibs, with the includedModule hint",
          rc == 0 and len(hints) == 2 and "org.example.core" not in "".join(grep(out, r"^hot packages")), f"{dt:.0f}s, {len(hints)} hints")
    rc, out, dt = gradle(":app:hotReloadRun")
    check("run", rc == 0 and "app started" in out)
    launch_checks(APP, "run (klibs from lib-build)")
    check("screen: core's string resource bundled from lib-build", has("Core: Core title v1"), str(label("Core")))
    edit("core", '"core-1 "', '"core-9 "')
    reload(APP, "a change in lib-build's core (linked as klib)", "WARM", "core-9", reason=r"^WARM external-klibs")
    stop(APP)


def phase_c():
    say("## C. the plugin applied inside the included build (:lib-build:demo), run from the root build")
    rc, out, dt = gradle(":lib-build:demo:hotReloadPackage")
    for l in grep(out, r"dependency klibs|hot packages|compose resources|package timing|BUILD"):
        say("  " + l)
    work_ok = os.path.isdir(os.path.join(P, "lib-build/demo/build/hotreload")) and not os.path.isdir(os.path.join(P, "build/hotreload"))
    check("package from the root build: work dir under lib-build/demo, core/util as project dependencies", rc == 0 and work_ok and "org.example.core" in out, f"{dt:.0f}s")
    rc, out, dt = gradle(":lib-build:demo:hotReloadRun")
    check("run: lib-build's local.properties (its own port)", rc == 0 and f"port {a.demo_port}" in out, "; ".join(grep(out, r"app started")).replace(P, "<P>")[:160])
    launch_checks(DEMO, "demo run")
    check("screen: demo and core", has("Demo: demo-1") and has("Core: Core title v1"), str([l for l in labels() if ":" in l][:3]))
    edit("demo", '"demo-1"', '"demo-2"')
    reload(DEMO, "demo body (included build's own app)", "HOT", "Demo: demo-2")
    edit("core_ios", '"core-ios-1"', '"core-ios-5"')
    reload(DEMO, "core actual through the demo app", "HOT", "core-ios-5")
    stop(DEMO)


def set_included(lines):
    s = open(f("app_build")).read()
    i = s.index("// HR_INCLUDED_MODULES_BEGIN") if "// HR_INCLUDED_MODULES_BEGIN" in s else None
    j = s.index("// HR_INCLUDED_MODULES_END")
    head = s[:i] if i is not None else s[:j]
    body = "// HR_INCLUDED_MODULES_BEGIN\ncmpHotReload {\n" + "".join(f"    {l}\n" for l in lines) + "}\n" if lines else ""
    open(f("app_build"), "w").write(head + body + s[j:])


def phase_d():
    say("## D. only :core declared: :util (which core uses) is linked as lib-build's klib")
    set_included(['includedModule("lib-build", ":core")'])
    rc, out, dt = gradle(":app:hotReloadPackage")
    hints = grep(out, r"comes from the included build lib-build")
    for l in grep(out, r"dependency klibs|hot packages|package timing|BUILD"):
        say("  " + l)
    entries = linked()
    core, util = mentions(entries, "core"), mentions(entries, "util")
    check("package: core compiled from source, util linked as lib-build's klib, each once; the hint names :util only",
          rc == 0 and len(core) == 1 and core[0][0] == "module" and len(util) == 1 and util[0][0] == "external"
          and len(hints) == 1 and ":util" in hints[0], f"core={[e[0] for e in core]} util={[e[0] for e in util]} hints={len(hints)}")
    rc, out, dt = gradle(":app:hotReloadRun")
    check("run", rc == 0 and "app started" in out)
    launch_checks(APP, "run (core from source, util as klib)")
    edit("core", '"core-9 "', '"core-10 "')
    reload(APP, "core body (declared) while util is a klib", "HOT", "core-10")
    edit("util", '"util-1"', '"util-7"')
    reload(APP, "util body (not declared: its klib changes)", "WARM", "core-10 util-7", reason=r"^WARM external-klibs")
    stop(APP)


def phase_e():
    say("## E. a wrong includedModule is reported before anything compiles")
    set_included(['includedModule("lib-buidl", ":core")'])
    rc, out, dt = gradle(":app:hotReloadPackage")
    check("wrong build name: the error lists the included builds", rc != 0 and re.search(r"no included build named lib-buidl \(included builds: \[[^\]]*lib-build[^\]]*\]\)", out) is not None
          and not grep(out, r"^> Task \S*(compileKotlinIos|hotReloadCompileKlib)"),
          "; ".join(grep(out, r"no included build named"))[:220])
    set_included(['includedModule("lib-build", ":cor")'])
    rc, out, dt = gradle(":app:hotReloadPackage")
    check("wrong path: the error lists the modules of that build, nothing compiled", rc != 0 and "modules found there: [:core, :demo, :util]" in out
          and not grep(out, r"^> Task \S*(compileKotlinIos|hotReloadCompileKlib)"),
          "; ".join(grep(out, r"modules found there"))[:220])
    set_included(['includedModule("lib-build", ":util")', 'includedModule("lib-build", ":core")'])


def phase_f():
    say("## F. a source set outside the default hierarchy (lib-build:util iosSharedMain)")
    rc, out, dt = gradle(":app:hotReloadPackage")
    warn = grep(out, r"lib-build:util has Kotlin source sets that hot reload does not compile")
    snippet = 'create("iosSharedMain") { srcDir("src/iosSharedMain/kotlin"); dependsOn("commonMain") }' in out
    check("default declaration: a warning names util/src/iosSharedMain/kotlin and shows the sourceSets to declare",
          rc == 0 and warn and "lib-build/util/src/iosSharedMain/kotlin" in warn[0] and snippet and 'includedModule("lib-build", ":util") {' in out,
          (warn[:1] or ["-"])[0].strip()[:200])
    set_included(['includedModule("lib-build", ":util") { sourceSets { create("commonMain") { srcDir("src/commonMain/kotlin") }; '
                  'create("iosSharedMain") { srcDir("src/iosSharedMain/kotlin"); dependsOn("commonMain") } } }',
                  'includedModule("lib-build", ":core")'])
    rc, out, dt = gradle(":app:hotReloadPackage")
    check("declared as suggested: no warning, package OK", rc == 0 and "hot reload does not compile" not in out, f"{dt:.0f}s")
    rc, out, dt = gradle(":app:hotReloadRun")
    check("run", rc == 0 and "app started" in out)
    s = open(f("core_ios")).read()
    tag = s[s.index("actual fun coreTag"):].split('"')[1]
    edit("core_ios", f'"{tag}"', f'"{tag} " + org.example.util.sharedLine()')
    reload(APP, "core's actual now calls util's iosSharedMain function", "HOT", f"{tag} shared-1")
    edit("shared", '"shared-1"', '"shared-2"')
    reload(APP, "an edit in util's iosSharedMain (declared source set)", "HOT", f"{tag} shared-2")
    stop(APP)
    set_included(['includedModule("lib-build", ":util")', 'includedModule("lib-build", ":core")'])


try:
    phase_a()
    phase_b()
    phase_c()
    phase_d()
    phase_e()
    phase_f()
except Exception as e:
    check(f"script error: {type(e).__name__}: {e}", False)
finally:
    say("## stop")
    stop(APP)
    stop(DEMO)
    say(f"apps running now: {pid(APP) is not None or pid(DEMO) is not None}")
    check("~/.konan/dependencies not touched (names and mtimes)", deps_listing() == deps_before)
    gap_after = os.path.getmtime(GAP) if os.path.exists(GAP) else None
    check("libKaldo wrote no SELREF debug log", gap_after == gap_before)
    cc = [o for o in OUTPUTS if "problems were found" in o or "Configuration cache problems" in o]
    check(f"configuration cache on (fixture gradle.properties) in all {len(OUTPUTS)} Gradle runs, no configuration cache problems",
          not cc and any("Configuration cache entry stored" in o or "Reusing configuration cache" in o for o in OUTPUTS), f"{len(cc)} runs with problems")
failed = [n for n, ok in results if not ok]
say(f"summary: {len(results) - len(failed)}/{len(results)} PASS" + ("" if not failed else f"; FAILED: {failed}"))
sys.exit(1 if failed else 0)
