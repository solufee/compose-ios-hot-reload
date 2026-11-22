#!/usr/bin/env python3
"""Issue #91: names that several modules' caches define, on the convention-plugin project (setup.py first).

Turns the project into the shape of the user's report and checks the three-tier rule end to end:
  - `:domain`, a module with no code of its own (a placeholder file and the fixture below), depending on `:core`;
  - `@Configuration` on `CoreModule` (`:core`) and on `AppModule` (`:shared`), `AppModule` includes `FeaturesModule`:
    Koin's compiler plugin then emits the same `org.koin.plugin.hints` functions in `:core`, `:domain` and `:shared`;
  - with --same-package, `FeaturesModule` sits in AppModule's file and package, so both generated `module` functions
    have a lambda called `module$1` (class 2: needs the toolchain that names local classes per file);
  Duplicated names that source code of hot modules declares are hot symbols, which the rule never localizes or weakens
  (they stop packaging as tier 3); the patch-side cases are in scripts/test-merge-payloads.py (section 5).

Steps (each prints RESULT/PASS/FAIL lines):
  1 clean package: rc 0, tier counts printed, the Koin hints and Compose's DefaultCacheSize in tier 1, no conflict
  2 cold start: Koin injects across the modules (greet, feat) and the placeholder module's function shows
  3 UI edit: HOT, same process
  4 :domain body edit: HOT
  5 :core body edit: HOT
  6 --rounds N: N HOT edits (UI text and a :core body, alternating), end-to-end time of each (Gradle start -> new line)
  7 --same-package only: two files of one package each have `fun X.module() = { ... }` (both lambdas are `module$1`, the
    shape Koin generates for AppModule and FeaturesModule); change only LamB's lambda, then only LamA's: each time just
    that one changes, the other stays

Usage: accept-dup-91.py PROJECT_DIR --udid U [--same-package] [--rounds N]
"""
import argparse
import os
import re
import statistics
import subprocess
import time

ap = argparse.ArgumentParser()
ap.add_argument("project")
ap.add_argument("--udid", required=True)
ap.add_argument("--same-package", action="store_true", help="FeaturesModule in AppModule's package (class 2, needs the fixed toolchain)")
ap.add_argument("--rounds", type=int, default=0)
a = ap.parse_args()
P = os.path.abspath(a.project)
BUNDLE, EXE = "org.example.kmphr.hotreload", "KmpHr"
LOG = os.path.join(P, "shared/launch.log")
HOME = os.path.expanduser("~")
REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../.."))
SHARED = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr")
CORE = os.path.join(P, "core/src/commonMain/kotlin/org/example/core")
checks = []


def clean(t):
    return t.replace(P, "<PROJECT>").replace(REPO, "<REPO>").replace(HOME, "<HOME>")


def say(t=""):
    print(clean(t), flush=True)


def check(name, ok, detail=""):
    checks.append((name, ok))
    say(f"  {'PASS' if ok else 'FAIL'} {name}" + (f" ({detail})" if detail else ""))


def write(rel, text):
    p = os.path.join(P, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write(text)


def edit(path, old, new):
    s = open(path).read()
    if old not in s:
        raise RuntimeError(f"{old!r} not in {path}")
    open(path, "w").write(s.replace(old, new, 1))


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


def wait_line(pred, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        ls = lines()
        if ls and pred(ls[-1]):
            return ls[-1], True
        time.sleep(0.2)
    ls = lines()
    return (ls[-1] if ls else "(no BOTAS line)"), False


def verdict_of(out):
    return "HOT" if "compat: HOT" in out else ("WARM" if re.search(r"^WARM |compat: WARM|WARM OK", out, re.M) else "-")


def tier_lines(out):
    return [l for l in out.splitlines() if l.startswith("duplicate definitions") or l.startswith("  tier") or l.startswith("    ")
            or l.startswith("patch: ")]


# ---------------------------------------------------------------- the #91 shape
write("domain/build.gradle.kts", 'plugins {\n    id("demo.kmp.library")\n}\n\nkotlin {\n    sourceSets.commonMain.dependencies {\n'
      '        api(project(":core"))\n    }\n}\n')
write("domain/src/commonMain/kotlin/org/example/domain/DomainPlaceholder.kt",
      "package org.example.domain\n\n/** Placeholder: the domain module has no code yet. */\nobject DomainPlaceholder\n")
write("domain/src/commonMain/kotlin/org/example/domain/DomainTag.kt", 'package org.example.domain\n\nfun domainTag(): String = "d1"\n')
settings = os.path.join(P, "settings.gradle.kts")
if 'include(":domain")' not in open(settings).read():
    edit(settings, 'include(":core")', 'include(":core")\ninclude(":domain")')
sb = os.path.join(P, "shared/build.gradle.kts")
if 'project(":domain")' not in open(sb).read():
    edit(sb, 'implementation(project(":core"))', 'implementation(project(":core"))\n        implementation(project(":domain"))')
coredi = os.path.join(CORE, "CoreDi.kt")
if "@Configuration" not in open(coredi).read():
    edit(coredi, "import org.koin.core.annotation.ComponentScan\n", "import org.koin.core.annotation.ComponentScan\nimport org.koin.core.annotation.Configuration\n")
    edit(coredi, '@Module\n@ComponentScan("org.example.core")\nclass CoreModule', '@Module\n@Configuration\n@ComponentScan("org.example.core")\nclass CoreModule')
di = os.path.join(SHARED, "di/Di.kt")
features_pkg = "org.example.kmphr.di" if a.same_package else "org.example.kmphr.features"
if "FeaturesModule" not in open(di).read():
    edit(di, "import org.koin.core.annotation.ComponentScan\n", "import org.koin.core.annotation.ComponentScan\nimport org.koin.core.annotation.Configuration\n")
    feat = '@Module\n@ComponentScan("org.example.kmphr.feature")\ninternal class FeaturesModule\n'
    if a.same_package:
        edit(di, '@Module\n@ComponentScan("org.example.kmphr.di")\nclass AppModule',
             '@Module(includes = [FeaturesModule::class])\n@Configuration\n@ComponentScan("org.example.kmphr.di")\nclass AppModule\n\n' + feat)
    else:
        edit(di, "import org.example.core.CoreModule\n", "import org.example.core.CoreModule\nimport org.example.kmphr.features.FeaturesModule\n")
        edit(di, '@Module\n@ComponentScan("org.example.kmphr.di")\nclass AppModule',
             '@Module(includes = [FeaturesModule::class])\n@Configuration\n@ComponentScan("org.example.kmphr.di")\nclass AppModule')
        write("shared/src/commonMain/kotlin/org/example/kmphr/features/FeaturesModule.kt",
              "package org.example.kmphr.features\n\nimport org.koin.core.annotation.ComponentScan\nimport org.koin.core.annotation.Module\n\n"
              + feat.replace("internal class", "class"))
write("shared/src/commonMain/kotlin/org/example/kmphr/feature/FeatureService.kt",
      'package org.example.kmphr.feature\n\nimport org.koin.core.annotation.Factory\n\n@Factory\nclass FeatureService {\n'
      '    fun name(): String = "f1"\n}\n')
app = os.path.join(SHARED, "App.kt")
if "domain=" not in open(app).read():
    src = open(app).read()
    m = re.search(r'println\("BOTAS (.*?)"\)', src)
    if not m:
        raise RuntimeError("no BOTAS println in App.kt")
    edit(app, m.group(0), 'println("BOTAS ' + m.group(1) + ' domain=${org.example.domain.domainTag()} '
         'feat=${featureName()}' + (' lam=${lambdaTags()}' if a.same_package else '') + ' ")')
    src = open(app).read()
    src += ('\n// Issue #91: FeatureService comes from FeaturesModule\'s generated module (a factory: every call runs its lambda).\n'
            'fun featureName(): String = runCatching { org.koin.mp.KoinPlatform.getKoin().get<org.example.kmphr.feature.FeatureService>().name() }'
            '.getOrElse { "ERR" }\n')
    if a.same_package:
        src += 'fun lambdaTags(): String = org.example.kmphr.lam.lamATag() + "/" + org.example.kmphr.lam.lamBTag()\n'
    open(app, "w").write(src)
if a.same_package:
    # class 2 without Koin: two files of one package, each a function named `module` with a lambda -> both `module$1`
    write("shared/src/commonMain/kotlin/org/example/kmphr/lam/LamA.kt",
          'package org.example.kmphr.lam\n\nclass LamA\n\nfun LamA.module(): () -> String = { "a1" }\n\nfun lamATag(): String = LamA().module()()\n')
    write("shared/src/commonMain/kotlin/org/example/kmphr/lam/LamB.kt",
          'package org.example.kmphr.lam\n\nclass LamB\n\nfun LamB.module(): () -> String = { "b1" }\n\nfun lamBTag(): String = LamB().module()()\n')

say(f"# #91 duplicate names across modules, {time.strftime('%F %T')}, simulator {a.udid}, same package: {a.same_package}")
subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)

# ---------------------------------------------------------------- 1 clean package
gradle("hotReloadClean")
rc, out, dt = gradle("hotReloadPackage")
say(f"RESULT 1) clean package: rc={rc} {dt:.0f}s")
for l in tier_lines(out):
    say("  | " + l)
check("1) hotReloadPackage rc=0", rc == 0, "" if rc == 0 else out[-1500:])
summary = next((l for l in out.splitlines() if l.startswith("duplicate definitions")), "")
check("1) tier counts printed", bool(summary), summary)
check("1) no conflict", "tier 3 conflict=0" in summary and "same library=0" in summary, summary)
for sym in ("org.koin.plugin.hints#configuration_default(org.example.core.CoreModule)",
            "org.koin.plugin.hints#componentscan_org_example_core_CoreModule_single", "<get-DefaultCacheSize>#internal"):
    tl = [l for l in out.splitlines() if l.startswith("  tier") and sym in l]
    check(f"1) {sym}: tier 1 local", bool(tl) and tl[0].startswith("  tier 1 local"), tl[0] if tl else "not listed")

# ---------------------------------------------------------------- 2..5
def step(name, fn, want, verdict_want, same_pid, task="hotReload", timeout=40):
    say(f"## {name}")
    if fn:
        fn()
    p0, n0 = pid(), len(lines())
    t0 = time.time()
    rc, out, dt = gradle(*task.split())
    # every state after the cold start must still have Koin's objects (greet, feat) and no caught exception
    line, reached = wait_line(lambda l: want(l) and "ERR" not in l and "greet=" in l, timeout)
    seen = time.time() - t0
    p1 = pid()
    v = verdict_of(out)
    say(f"RESULT {name}: rc={rc} gradle {dt:.1f}s seen {seen:.1f}s verdict={v} pid {p0}->{p1} new BOTAS lines={len(lines()) - n0}")
    say(f"  app: {line}")
    for l in [l for l in out.splitlines() if any(k in l for k in ("compat:", "WARM ", "-> ", "patch: ", "duplicate definitions", "  tier",
                                                                 "error:", "e: ", "FAILURE"))][:30]:
        say("  | " + l)
    check(f"{name}: gradle rc=0", rc == 0)
    if verdict_want:
        check(f"{name}: verdict {verdict_want}", v == verdict_want, f"got {v}")
    if same_pid is not None:
        check(f"{name}: process {'kept' if same_pid else 'restarted'}", p1 is not None and (p0 == p1) == same_pid, f"{p0}->{p1}")
    check(f"{name}: app shows the expected state", reached, line[:200])
    return out, seen


if rc == 0:
    step("2) cold start", None, lambda l: "domain=d1" in l and "feat=f1" in l and "greet=g1" in l, None, None, task="hotReloadRun", timeout=90)
    ui = re.search(r'UI_TAG = "(u\d+)"', open(app).read()).group(1)
    step("3) UI edit", lambda: edit(app, f'UI_TAG = "{ui}"', 'UI_TAG = "u91"'), lambda l: "ui=u91" in l, "HOT", True)
    step("4) :domain (the placeholder module) body edit", lambda: edit(os.path.join(P, "domain/src/commonMain/kotlin/org/example/domain/DomainTag.kt"),
         '"d1"', '"d2"'), lambda l: "domain=d2" in l, "HOT", True)
    step("5) :core body edit (the module whose @Configuration hints are regenerated downstream)",
         lambda: edit(coredi, 'return t + "/" + ', 'return t + "|91|" + '), lambda l: "|91|" in l, "HOT", True)

    if a.rounds:
        times = []
        extra = os.path.join(CORE, "CoreDi.kt")
        tag = re.search(r'fun tag\(\): String = "(k\d+)"', open(extra).read()).group(1)
        for i in range(a.rounds):
            if i % 2 == 0:
                _, seen = step(f"6.{i + 1}) UI text u91-{i}", lambda i=i: edit(app, re.search(r'UI_TAG = "[^"]+"', open(app).read()).group(0), f'UI_TAG = "r{i}"'),
                               lambda l, i=i: f"ui=r{i} " in l, "HOT", True)
            else:
                old = re.search(r'fun greet\(\): String = "[^"]+"', open(os.path.join(SHARED, "di/Di.kt")).read()).group(0)
                _, seen = step(f"6.{i + 1}) :shared GreeterImpl body", lambda old=old, i=i: edit(os.path.join(SHARED, "di/Di.kt"), old, f'fun greet(): String = "g9{i}"'),
                               lambda l, i=i: f"greet=g9{i} " in l, "HOT", True)
            times.append(seen)
        say(f"RESULT 6) {len(times)} rounds: end-to-end median {statistics.median(times):.2f}s max {max(times):.2f}s "
            f"(Gradle start -> new BOTAS line)")
        check("6) end-to-end median <= 6 s", statistics.median(times) <= 6.0, f"{statistics.median(times):.2f}s")
        check("6) end-to-end max <= 10 s", max(times) <= 10.0, f"{max(times):.2f}s")

    if a.same_package:
        lam = os.path.join(SHARED, "lam")
        step("7a) only the lambda in LamB.module (module$1 of LamB.kt): LamA's lambda stays",
             lambda: edit(os.path.join(lam, "LamB.kt"), '"b1"', '"b2"'), lambda l: "lam=a1/b2" in l, "HOT", True)
        step("7b) only the lambda in LamA.module (module$1 of LamA.kt): LamB's lambda stays",
             lambda: edit(os.path.join(lam, "LamA.kt"), '"a1"', '"a2"'), lambda l: "lam=a2/b2" in l, "HOT", True)

subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
gradle("hotReloadDaemonStop")
say("# app and compile daemons stopped")
n = sum(1 for _, ok in checks if ok)
say(f"summary: {n}/{len(checks)} PASS")
