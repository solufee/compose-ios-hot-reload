#!/usr/bin/env python3
"""#129: hotReload compiles the library klibs in its own action and skips a module whose compile inputs' metadata is
unchanged. Every case states what must happen (expected) and records what did (actual).

Project: the wizard template turned into the convention-plugin project (setup.py) with the #91 shape (:core, :domain,
:shared), plus
  - core/CoreApi.kt `fun coreApi(): String`, used by domain/DomainUse.kt, shown by the app as `use=`;
  - a generated source in :core: task genHrSource writes build/generated/hrgen/.../Gen.kt (`generatedTag()`) from
    core/gen-input.txt, registered as a commonMain source directory; shown as `gen=`;
  - a git repository (for the branch-switch case).
Usage: accept-klib-in-reload.py PROJECT_DIR WIZARD_ZIP PLUGIN_REPO PLUGIN_VERSION TOOLCHAIN_DIR UDID [PORT]
"""
import os
import re
import subprocess
import sys
import time
import zipfile
import shutil

P, ZIP, REPO, V, TC, U = [os.path.abspath(x) if i in (0, 1) else x for i, x in enumerate(sys.argv[1:7])]
PORT = sys.argv[7] if len(sys.argv) > 7 else "5597"
HERE = os.path.dirname(os.path.abspath(__file__))

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
                "--udid", U, "--port", PORT], check=True)

# the #91 shape and its helpers (say, check, write, edit, gradle, pid, lines, wait_line, verdict_of)
src = open(os.path.join(HERE, "accept-dup-91.py")).read()
cut = src.index("# ---------------------------------------------------------------- 1 clean package")
sys.argv = [os.path.join(HERE, "accept-dup-91.py"), P, "--udid", U]
exec(compile(src[:cut], "accept-dup-91-shape", "exec"))

# ---------------------------------------------------------------- #129 additions to the project
write("core/src/commonMain/kotlin/org/example/core/CoreApi.kt", 'package org.example.core\n\nfun coreApi(): String = "c1"\n')
write("domain/src/commonMain/kotlin/org/example/domain/DomainUse.kt",
      'package org.example.domain\n\nfun domainUse(): String = org.example.core.coreApi()\n')
write("core/gen-input.txt", "g1\n")
cb = os.path.join(P, "core/build.gradle.kts")
open(cb, "a").write('''
// #129 acceptance: a generated source (Gen.kt) from an input file, as code generators do
val genHrSource = tasks.register("genHrSource") {
    val input = layout.projectDirectory.file("gen-input.txt")
    val out = layout.buildDirectory.dir("generated/hrgen/kotlin")
    inputs.file(input)
    outputs.dir(out)
    doLast {
        val tag = input.asFile.readText().trim()
        val f = out.get().file("org/example/core/Gen.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package org.example.core\\n\\nfun generatedTag(): String = \\"" + tag + "\\"\\n")
    }
}
kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(genHrSource)
''')
app = os.path.join(SHARED, "App.kt")
s = open(app).read()
m = re.search(r'println\("BOTAS (.*?)"\)', s)
edit(app, m.group(0), 'println("BOTAS ' + m.group(1) + ' use=${org.example.domain.domainUse()} gen=${org.example.core.generatedTag()} ")')
open(os.path.join(P, ".gitignore"), "a").write("\nbuild/\n.gradle/\n.kotlin/\n.hr/\n*.log\nlocal.properties\n")
subprocess.run("git init -q -b main && git add -A && git -c user.name=t -c user.email=t@t commit -q -m base", shell=True, cwd=P, check=True)

DOMAIN = os.path.join(P, "domain/src/commonMain/kotlin/org/example/domain")
CORE_API = os.path.join(CORE, "CoreApi.kt")
TAG = os.path.join(DOMAIN, "DomainTag.kt")
say(f"# #129 acceptance (klibs in the reload), {time.strftime('%F %T')}, plugin {V}, simulator {U}")


def klib_lines(out):
    return [l for l in out.splitlines() if l.startswith("klib ") or l.startswith("klibs: ")]


def compiled(out, label):
    return any(l.startswith(f"klib {label}: compiling") for l in out.splitlines())


def summary(out):
    return next((l for l in out.splitlines() if l.startswith("klibs: ")), "(no klibs: line)")


def case(name, expected, fn, task="hotReload", want=None, verdict=None, klibs=None, timeout=60):
    """klibs: {label: 'compiled' | 'checked' | 'skipped'} expected for this reload."""
    say(f"## {name}")
    say(f"  expected: {expected}")
    if fn:
        fn()
    n0 = len(lines())
    rc, out, dt = gradle(*task.split())
    line, reached = (wait_line(want, timeout) if want else (lines()[-1] if lines() else "(no BOTAS line)", True))
    v = verdict_of(out)
    sm = summary(out)
    say(f"  actual: rc={rc} {dt:.1f}s verdict={v} | {sm}")
    for l in klib_lines(out):
        if not l.startswith("klibs: "):
            say("  | " + l)
    for l in [l for l in out.splitlines() if "build identity" in l or "FAILURE" in l or l.startswith("e: ") or "nothing changed" in l][:5]:
        say("  | " + l)
    say(f"  app: {line[:200]}")
    for label, how in (klibs or {}).items():
        got = "compiled" if compiled(out, label) else ("skipped" if re.search(rf"skipped: .*\b{label}\b", sm) else
                                                       ("checked" if f"klib {label}: inputs unchanged" in out else "not in this reload"))
        check(f"{name}: klib {label} {how}", got == how, f"got {got}")
    if verdict:
        check(f"{name}: verdict {verdict}", v == verdict, f"got {v}")
    if want:
        check(f"{name}: app shows the expected state", reached, line[:160])
    return rc, out


# ---------------------------------------------------------------- package + run
rc, out, dt = gradle("hotReloadPackage")
say(f"RESULT package: rc={rc} {dt:.0f}s")
check("package rc=0", rc == 0, "" if rc == 0 else out[-1500:])
rc, out, dt = gradle("hotReloadRun")
line, ok = wait_line(lambda l: "domain=d1" in l and "use=c1" in l and "gen=g1" in l, 90)
check("run: app shows domain=d1 use=c1 gen=g1", rc == 0 and ok, line[:200])
ui_n = [0]


def ui_edit():
    ui_n[0] += 1
    edit(app, re.search(r'UI_TAG = "[^"]+"', open(app).read()).group(0), f'UI_TAG = "k{ui_n[0]}"')


# 1 the first reload records the stamps; the second is the steady state
case("1a) app edit (first reload: libraries checked, stamps recorded)", "core and domain checked (inputs unchanged), HOT",
     ui_edit, want=lambda l: "ui=k1" in l, verdict="HOT", klibs={"core": "checked", "domain": "checked"})
case("1b) app edit (steady state)", "core and domain skipped by file metadata, HOT",
     ui_edit, want=lambda l: "ui=k2" in l, verdict="HOT", klibs={"core": "skipped", "domain": "skipped"})

# 2 content changed, mtime set back (touch -r)
def rollback():
    st = os.stat(TAG)
    time.sleep(1.1)
    edit(TAG, '"d1"', '"d2"')                 # same size
    os.utime(TAG, ns=(st.st_atime_ns, st.st_mtime_ns))
    say(f"  (DomainTag.kt: size {st.st_size}->{os.stat(TAG).st_size}, mtime {st.st_mtime_ns}->{os.stat(TAG).st_mtime_ns}, ctime {st.st_ctime_ns}->{os.stat(TAG).st_ctime_ns})")
case("2) domain content changed, mtime set back (touch -r)", "domain recompiled (ctime moved), HOT, app domain=d2",
     rollback, want=lambda l: "domain=d2" in l, verdict="HOT", klibs={"core": "skipped", "domain": "compiled"})

# 3 build file only: a comment (no compile input changes) and a compiler plugin option
db = os.path.join(P, "domain/build.gradle.kts")
case("3a) domain/build.gradle.kts: a comment only", "domain not skipped (its build file changed) but checked and unchanged; HOT",
     lambda: (ui_edit(), open(db, "a").write("\n// #129 acceptance: a comment\n")), want=lambda l: f"ui=k{ui_n[0]}" in l, verdict="HOT",
     klibs={"domain": "checked", "core": "skipped"})
rc3, out3 = case("3b) domain/build.gradle.kts: Koin compiler plugin option debugLogs=true (a compile option)",
                 "domain recompiled (its compiler arguments changed); the option changes no code, so nothing is sent; the changed build "
                 "identity is reported, not acted on, during a hot reload (#98); hotReloadPackage hotReloadRun then rebuilds",
                 lambda: open(db, "a").write("\nconfigure<org.koin.compiler.plugin.KoinGradleExtension> { debugLogs.set(true) }\n"),
                 klibs={"domain": "compiled", "core": "skipped"})
check("3b) the changed build identity is reported", rc3 == 0 and "build identity changed since the app was packaged" in out3,
      next((l for l in out3.splitlines() if "build identity" in l), "")[:200])
rc, out, dt = gradle("hotReloadPackage", "hotReloadRun")
check("3b) hotReloadPackage hotReloadRun: domain recompiled", rc == 0 and "klib domain: compiling" in out,
      "; ".join(l for l in out.splitlines() if l.startswith("klib domain:"))[:200])
line, ok = wait_line(lambda l: "domain=d2" in l, 90)
check("3b) app back with domain=d2", ok, line[:160])
case("3c) app edit after the package (stamps from the package are not trusted)", "core and domain checked, HOT",
     ui_edit, want=lambda l: f"ui=k{ui_n[0]}" in l, verdict="HOT", klibs={"core": "checked", "domain": "checked"})

# 4/5 a new source file, then its removal
NEW = os.path.join(DOMAIN, "DomainExtra.kt")
case("4) new source file in domain", "domain recompiled, HOT", lambda: write(os.path.relpath(NEW, P), 'package org.example.domain\n\nfun domainExtra(): String = "x1"\n'),
     verdict="HOT", klibs={"domain": "compiled", "core": "skipped"})
rc5, out5 = case("5) that file deleted", "domain recompiled; the removed function was not used, so the patch has nothing to send (nothing changed)",
                 lambda: os.remove(NEW), klibs={"domain": "compiled", "core": "skipped"})
check("5) nothing to send", rc5 == 0 and "nothing changed" in out5, next((l for l in out5.splitlines() if "nothing changed" in l), "")[:160])

# 6 the input of a generated source
case("6) generated source input changed (core/gen-input.txt g1 -> g2)", "core recompiled (Gen.kt regenerated), domain checked (only a body changed upstream: #124), HOT, app gen=g2",
     lambda: open(os.path.join(P, "core/gen-input.txt"), "w").write("g2\n"), want=lambda l: "gen=g2" in l, verdict="HOT",
     klibs={"core": "compiled", "domain": "checked"})

# 7 git branch switch, then a one-line edit
def branch_switch():
    subprocess.run("git add -A && git -c user.name=t -c user.email=t@t commit -q -m 'state' && git checkout -q -b other && "
                   "sed -i '' 's/\"d2\"/\"d9\"/' domain/src/commonMain/kotlin/org/example/domain/DomainTag.kt && "
                   "git -c user.name=t -c user.email=t@t commit -qam other && git checkout -q main", shell=True, cwd=P, check=True)
case("7a) git: another branch changed DomainTag.kt, back on main (same content, new mtime)", "domain checked and unchanged (contents as compiled), nothing to send",
     branch_switch, klibs={"domain": "checked", "core": "skipped"})
case("7b) then a one-line edit (domainTag d2 -> d4)", "domain recompiled, HOT, app domain=d4",
     lambda: edit(TAG, '"d2"', '"d4"'), want=lambda l: "domain=d4" in l, verdict="HOT", klibs={"domain": "compiled", "core": "skipped"})

# 8 #124 kept: a body change upstream does not recompile domain; a signature change does (hotReload only, no package)
case("8a) core body change (coreApi c1 -> c2)", "core recompiled, domain checked and unchanged (ABI fingerprint, #124), HOT, app use=c2",
     lambda: edit(CORE_API, '"c1"', '"c2"'), want=lambda l: "use=c2" in l, verdict="HOT", klibs={"core": "compiled", "domain": "checked"})
case("8b) core signature change (coreApi(n: Int = 3)), hotReload only", "core and domain recompiled (ABI changed), app use=c3",
     lambda: edit(CORE_API, 'fun coreApi(): String = "c2"', 'fun coreApi(n: Int = 3): String = "c$n"'),
     want=lambda l: "use=c3" in l, klibs={"core": "compiled", "domain": "compiled"})

# 9 the switch back to one Gradle task per module
rc, out = case("9) -Pcmp.hotreload.klibInReload=false", "one Gradle task per library module (hotReloadCompileKlib...), no 'klibs:' line, HOT",
               ui_edit, task="hotReload -Pcmp.hotreload.klibInReload=false", want=lambda l: f"ui=k{ui_n[0]}" in l, verdict="HOT")
check("9) per-module tasks ran, no in-reload pass", "hotReloadCompileKlibDomain" in out and "klibs: " not in out,
      "; ".join(l for l in out.splitlines() if "hotReloadCompileKlib" in l)[:200])

subprocess.run(["xcrun", "simctl", "terminate", U, BUNDLE], capture_output=True)
gradle("hotReloadDaemonStop")
failed = [n for n, ok in checks if not ok]
say(f"summary: {len(checks) - len(failed)}/{len(checks)} PASS" + ("" if not failed else f"; FAILED: {failed}"))
sys.exit(1 if failed else 0)
