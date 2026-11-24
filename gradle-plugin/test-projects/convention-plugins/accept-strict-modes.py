#!/usr/bin/env python3
"""#137 (fixed release gate from 0.2.14): a multi-module project in the botas shape (convention plugins in build-logic,
:core library + :shared app, Koin compiler plugin, kotlinx-serialization, kotlinx-rpc) with the strictest modes a project
can set, all at once in both modules: explicitApi(), allWarningsAsErrors, progressiveMode. Cold package, run, then a
one-line edit in the app module and one in :core, each HOT in the same process.

0.2.13 failed here at the package: the generated HotReloadMain.kt had no visibility modifiers (explicit API), and the
package compile's split scheme printed a compiler warning that -Werror turned into an error.

To make the normal build pass under these modes, the template's sources get `public` (explicit API), and the Koin
compiler plugin's own notices are set to info (its documented switch for allWarningsAsErrors).

Usage: accept-strict-modes.py PROJECT_DIR WIZARD_ZIP PLUGIN_REPO PLUGIN_VERSION TOOLCHAIN_DIR UDID [PORT]
"""
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

P, ZIP, REPO, V, TC, U = [os.path.abspath(x) if i in (0, 1) else x for i, x in enumerate(sys.argv[1:7])]
PORT = sys.argv[7] if len(sys.argv) > 7 else "5568"
HERE = os.path.dirname(os.path.abspath(__file__))
BUNDLE = "org.example.kmphr.hotreload"
LOG = os.path.join(P, "shared/launch.log")
results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("  PASS " if ok else "  FAIL ") + name + (f" ({detail})" if detail else ""), flush=True)
    return ok


def gradle(*args):
    t = time.time()
    r = subprocess.run(["./gradlew", *args, "--console=plain"], cwd=P, capture_output=True, text=True)
    out = r.stdout + r.stderr
    with open(os.path.join(P, "..", f"strict-{'-'.join(a.strip('-:') for a in args)[:40]}.log"), "a") as f:
        f.write(out)
    print(f"RESULT gradle {' '.join(args)}: rc={r.returncode} {time.time() - t:.1f}s", flush=True)
    return r.returncode, out


def app_pid():
    r = subprocess.run(["xcrun", "simctl", "spawn", U, "launchctl", "list"], capture_output=True, text=True)
    for l in r.stdout.splitlines():
        if BUNDLE in l:
            f = l.split()
            return f[0] if f[0].isdigit() else None
    return None


def wait_log(pattern, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        if os.path.isfile(LOG) and re.search(pattern, open(LOG, errors="replace").read()):
            return True
        time.sleep(0.5)
    return False


def edit(path, old, new):
    f = os.path.join(P, path)
    s = open(f).read()
    assert old in s, f"{old!r} not in {path}"
    open(f, "w").write(s.replace(old, new, 1))


# ---------------------------------------------------------------- project
shutil.rmtree(P, ignore_errors=True)
os.makedirs(P)
with zipfile.ZipFile(ZIP) as z:
    z.extractall(P)
inner = os.path.join(P, os.listdir(P)[0])
for n in os.listdir(inner):
    shutil.move(os.path.join(inner, n), P)
os.rmdir(inner)
os.chmod(os.path.join(P, "gradlew"), 0o755)
subprocess.run([sys.executable, os.path.join(HERE, "setup.py"), P, "--plugin-repo", REPO, "--plugin-version", V, "--toolchain", TC,
                "--udid", U, "--port", PORT], check=True, stdout=subprocess.DEVNULL)

STRICT = """
// #137: the strictest modes a project can set, all at once
kotlin {
    explicitApi()
    compilerOptions {
        allWarningsAsErrors.set(true)
        progressiveMode.set(true)
    }
}

// The Koin compiler plugin's own switch for allWarningsAsErrors (its notices as info, not warnings)
extensions.configure<org.koin.compiler.plugin.KoinGradleExtension>("koinCompiler") {
    versionCheckSeverity.set("info")
    logSeverity.set("info")
}
"""
for m in ("core", "shared"):
    with open(os.path.join(P, m, "build.gradle.kts"), "a") as f:
        f.write(STRICT)

# explicit API: every declaration of the template's sources gets `public` (locals and overrides stay as they are)
decl = re.compile(r"^(\s*)(?!public |private |internal |override )((?:data |sealed |suspend |const )*(?:class|interface|object|fun) )")
for root, _, files in os.walk(P):
    if "/build" in root or "build-logic" in root or "/src/" not in root + "/":
        continue
    for n in files:
        if not n.endswith(".kt"):
            continue
        f = os.path.join(root, n)
        lines = open(f).read().split("\n")
        out = []
        for l in lines:
            if l.strip().startswith(("//", "*", "/*", "import", "package")):
                out.append(l)
                continue
            l = decl.sub(r"\1public \2", l)
            l = re.sub(r"\{ fun ", "{ public fun ", l)
            l = l.replace("const val UI_TAG = ", "public const val UI_TAG: String = ")
            out.append(l)
        open(f, "w").write("\n".join(out))

print("== 1 normal build under explicitApi + allWarningsAsErrors + progressiveMode", flush=True)
rc, out = gradle(":core:compileKotlinIosSimulatorArm64", ":shared:compileKotlinIosSimulatorArm64")
check("1) the normal build compiles both modules", rc == 0, "\n".join(l for l in out.splitlines() if l.startswith(("e:", "w:")))[:400])

print("== 2 cold package", flush=True)
rc, out = gradle("hotReloadPackage", "--info")
check("2) hotReloadPackage succeeds", rc == 0, "\n".join(l for l in out.splitlines() if "error:" in l or "FAILED" in l)[:600])
compiles = [l.split(": ", 1)[1].split() for l in out.splitlines() if l.startswith("compile args")]
libs = [a for a in compiles if a[a.index("-p") + 1] == "library"]
progs = [a for a in compiles if a[a.index("-p") + 1] == "program"]
check("2a) every klib compile has -Werror, -progressive and -Xexplicit-api=strict",
      bool(libs) and all({"-Werror", "-progressive", "-Xexplicit-api=strict"} <= set(a) for a in libs), f"{len(libs)} klib compiles")
check("2b) the package compile has -progressive and -Xexplicit-api=strict, not -Werror (split scheme)",
      bool(progs) and all({"-progressive", "-Xexplicit-api=strict"} <= set(a) and "-Werror" not in a for a in progs), f"{len(progs)} program compiles")
gen = os.path.join(P, "shared/build/hotreload/generated/kotlin/HotReloadMain.kt")
g = open(gen).read() if os.path.isfile(gen) else ""
check("2c) the generated HotReloadMain.kt is explicit-API clean", "internal class HotReloadAppDelegate" in g and "public fun main(" in g)
check("2d) no compiler warning in the package output", not any(re.search(r"(^|\s)warning: ", l) and "HotReloadMain" in l for l in out.splitlines()))

print("== 3 run", flush=True)
if os.path.isfile(LOG):
    os.remove(LOG)
rc, out = gradle("hotReloadRun")
pid0 = app_pid()
check("3) hotReloadRun starts the app", rc == 0 and pid0 is not None, f"pid {pid0}")
check("3a) the app shows ui=u1 and its server round trip", wait_log(r"BOTAS ui=u1 .*server=.*h1"))

print("== 4 one-line edit in the app module (UI_TAG u1 -> u2)", flush=True)
edit("shared/src/commonMain/kotlin/org/example/kmphr/App.kt", '"u1"', '"u2"')
rc, out = gradle("hotReload")
check("4) hotReload HOT", rc == 0 and "compat: HOT" in out, next((l for l in out.splitlines() if l.startswith("compat:")), ""))
check("4a) same process, new code shown", app_pid() == pid0 and wait_log(r"BOTAS ui=u2 "), f"pid {pid0} -> {app_pid()}")

print("== 5 one-line edit in :core (SavedServer h1 -> h2)", flush=True)
edit("core/src/commonMain/kotlin/org/example/core/CoreDi.kt", 'SavedServer("h1", "n1")', 'SavedServer("h2", "n1")')
rc, out = gradle("hotReload")
check("5) hotReload HOT (core klib recompiled with -Werror)", rc == 0 and "compat: HOT" in out and re.search(r"klibs: [1-9]\d* compiled", out) is not None,
      " / ".join(l for l in out.splitlines() if l.startswith(("compat:", "klibs:"))))
check("5a) same process, new code shown", app_pid() == pid0 and wait_log(r"BOTAS ui=u2 .*server=.*h2"), f"pid {pid0} -> {app_pid()}")

subprocess.run(["xcrun", "simctl", "terminate", U, BUNDLE], capture_output=True)
gradle("hotReloadDaemonStop")
print("# app and compile daemons stopped", flush=True)
print(f"summary: {sum(results)}/{len(results)} {'PASS' if all(results) else 'FAIL'}", flush=True)
sys.exit(0 if all(results) else 1)
