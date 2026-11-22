#!/usr/bin/env python3
"""Plugin acceptance for #98: after a plugin or toolchain upgrade, without hotReloadClean, the hot reload state is rebuilt
and the results match a fresh build directory.

  G1  plugin upgrade, same toolchain: --old-plugin -> --new-plugin (toolchain --tc-new)
  G2  toolchain replaced in place: one toolchain directory path; first --tc-old with --old-pin-plugin, then the same path
      with --tc-new's contents and --new-plugin
      control: the same with --ctl-old -> --ctl-new (plugins without #98): the stale state is reused
  R   nothing changed: a second package does not recompile (#21 unchanged)
Each upgrade: `hotReloadPackage`, then `hotReloadRun`, then one edit with `hotReload` (HOT, same process), then the klib sha256
of the upgraded directory against a clean rebuild (hotReloadClean + hotReloadPackage) of the same project.

Usage: accept-build-identity.py --template KMP_PROJECT_WITH_CORE --work DIR --plugin-repo URL --udid U
         --tc-old DIST_ROOT --tc-new DIST_ROOT --old-plugin V --new-plugin V --old-pin-plugin V --ctl-old V --ctl-new V
(DIST_ROOT: a fetched toolchain version directory, the parent of kotlin-native.)
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time

ap = argparse.ArgumentParser()
for k in ("template", "work", "plugin-repo", "udid", "tc-old", "tc-new", "old-plugin", "new-plugin", "old-pin-plugin", "ctl-old", "ctl-new"):
    ap.add_argument("--" + k, required=True)
ap.add_argument("--port", default="5568")
a = ap.parse_args()
WORK = os.path.abspath(a.work)
HOME = os.path.expanduser("~")
REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../.."))
EXE, BUNDLE = "KmpHr", "org.example.kmphr.hotreload"
results = []


def say(line=""):
    print(line.replace(WORK, "<WORK>").replace(REPO, "<REPO>").replace(HOME, "<HOME>"), flush=True)


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    say(f"{'PASS' if ok else 'FAIL'} {name}" + (f" | {detail}" if detail else ""))


def project(name, plugin, dist):
    p = os.path.join(WORK, name)
    shutil.copytree(a.template, p, ignore=shutil.ignore_patterns("build", ".gradle", ".kotlin", "launch.log"))
    s = open(os.path.join(p, "settings.gradle.kts")).read()
    s = re.sub(r'maven \{ url = uri\("[^"]*"\) \}', f'maven {{ url = uri("{a.plugin_repo}") }}', s, count=1)
    open(os.path.join(p, "settings.gradle.kts"), "w").write(s)
    set_plugin(p, plugin)
    open(os.path.join(p, "local.properties"), "w").write(
        f"cmp.hotreload.toolchainDir={dist}/kotlin-native\ncmp.hotreload.simulatorUdid={a.udid}\ncmp.hotreload.port={a.port}\n")
    return p


def set_plugin(p, version):
    f = os.path.join(p, "shared/build.gradle.kts")
    s = re.sub(r'id\("com.solufee.cmp.hotreload"\) version "[^"]*"', f'id("com.solufee.cmp.hotreload") version "{version}"', open(f).read(), count=1)
    open(f, "w").write(s)


def gradle(p, *args):
    t0 = time.time()
    r = subprocess.run([os.path.join(p, "gradlew"), "-p", p, *args, "--console=plain"], cwd=p, capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr, time.time() - t0


def pid():
    o = subprocess.run(["pgrep", "-f", f"Devices/{a.udid}/.*/{EXE}.app/{EXE}"], capture_output=True, text=True).stdout.split()
    return o[0] if o else None


def lines(out, pat):
    return [l for l in out.splitlines() if re.search(pat, l)]


def tail(out, n=15):
    for l in [l for l in out.splitlines() if l.strip()][-n:]:
        say("  ! " + l[:300])


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()[:16] if os.path.isfile(path) else "missing"


def klibs(p):
    hr = os.path.join(p, "shared/build/hotreload")
    return {"core.klib": sha(os.path.join(hr, "klib/core/core.klib")), "app.klib": sha(os.path.join(hr, "compat/app.klib"))}


def oldest_cache_mtime(p):
    """Oldest file of the compiler's caches: ic/ (rewritten by every compile) and auto-cache/ (library caches, kept while
    the compiler thinks they are valid; the bulk of the compile time)."""
    hr = os.path.join(p, "shared/build/hotreload")
    ts = [os.path.getmtime(os.path.join(r, f)) for d in ("ic", "auto-cache") for r, _, fs in os.walk(os.path.join(hr, d)) for f in fs]
    return min(ts) if ts else None


def cache_files_before(p, t):
    hr = os.path.join(p, "shared/build/hotreload")
    return {d: sum(1 for r, _, fs in os.walk(os.path.join(hr, d)) for f in fs if os.path.getmtime(os.path.join(r, f)) < t) for d in ("ic", "auto-cache")}


EDIT_N = [0]


def edit(p):
    """One UI text edit in App.kt (HOT)."""
    f = os.path.join(p, "shared/src/commonMain/kotlin/org/example/kmphr/App.kt")
    s = open(f).read()
    EDIT_N[0] += 1
    s2 = re.sub(r'Text\("Click me![^"]*"\)', f'Text("Click me! {EDIT_N[0]}")', s, count=1)
    if s2 == s:
        raise RuntimeError("no Click me! text in App.kt")
    open(f, "w").write(s2)


def run_and_edit(p, label):
    # two commands: plugins before 0.2.8 (#76) do not order hotReloadRun after hotReloadPackage within one command
    rc, out, dt = gradle(p, "hotReloadPackage")
    if rc == 0:
        rc, out2, dt2 = gradle(p, "hotReloadRun")
        out, dt = out + out2, dt + dt2
    for l in lines(out, r"build identity|klib core:|app klib \(classifier\)|toolchain OK|package timing"):
        say("  | " + l[:300])
    ok = rc == 0 and "app started" in out
    check(f"{label}: package + run", ok, f"{dt:.0f}s")
    if not ok:
        tail(out)
    p0 = pid()
    edit(p)
    rc2, out2, dt2 = gradle(p, "hotReload")
    ok = rc2 == 0 and "compat: HOT" in out2 and pid() == p0
    check(f"{label}: one edit is HOT in the same process", ok, f"{dt2:.1f}s pid {p0}->{pid()}")
    if not ok:
        tail(out2)
    return out


def swap(root, src):
    """The same toolchain directory path, other contents (a toolchain unpacked again into the same place)."""
    shutil.rmtree(root, ignore_errors=True)
    subprocess.run(["cp", "-cR", src, root], check=True)


def fresh_matches(p, label):
    """The upgraded state against a clean rebuild of the same project (klibs embed source paths, so the same directory)."""
    up = klibs(p)
    rc, out, dt = gradle(p, "hotReloadClean", "hotReloadPackage")
    fr = klibs(p)
    check(f"{label}: klibs equal a clean rebuild (hotReloadClean + hotReloadPackage, same plugin, same toolchain)", rc == 0 and up == fr,
          f"upgraded {up} clean {fr}")
    if rc != 0:
        tail(out)


say(f"# #98 acceptance (build identity), {time.strftime('%F %T')}, simulator {a.udid}")
shutil.rmtree(WORK, ignore_errors=True)
os.makedirs(WORK)
try:
    say(f"## G1 plugin upgrade {a.old_plugin} -> {a.new_plugin}, toolchain unchanged (no hotReloadClean)")
    p1 = project("g1", a.old_plugin, a.tc_new)
    run_and_edit(p1, f"G1 before ({a.old_plugin})")
    set_plugin(p1, a.new_plugin)
    out = run_and_edit(p1, f"G1 after ({a.new_plugin})")
    ident = lines(out, r"build identity changed")
    check("G1: the upgrade clears the old state with a reason", ident and "no build identity recorded" in ident[0], (ident or ["-"])[0][:240])
    check("G1: module klib recompiled", bool(lines(out, r"klib core: compiling")))
    ident_file = os.path.join(p1, "shared/build/hotreload/build-identity.txt")
    kgp = [l for l in open(ident_file).read().splitlines() if l.startswith("projectKotlin ")] if os.path.isfile(ident_file) else []
    check("G1: the identity records the project's Kotlin Gradle plugin version", kgp and re.match(r"projectKotlin \d+\.\d+", kgp[0]), (kgp or ["-"])[0])
    fresh_matches(p1, "G1")

    say("## R nothing changed: package again")
    rc, out, dt = gradle(p1, "hotReloadPackage")
    check("R: no identity change, the module klib is not recompiled",
          rc == 0 and not lines(out, r"build identity changed") and not lines(out, r"klib core: compiling")
          and bool(lines(out, r"hotReloadCompileKlibCore UP-TO-DATE|klib core: inputs unchanged")),
          (lines(out, r"hotReloadCompileKlibCore|klib core:") or ["-"])[0][:200])
    gradle(p1, "hotReloadDaemonStop")

    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
    T = os.path.join(WORK, "toolchain-in-place")
    say(f"## G2 control (plugins without #98): {a.ctl_old} on the old toolchain, then the same path with the new toolchain and {a.ctl_new}")
    swap(T, a.tc_old)
    pc = project("g2-control", a.ctl_old, T)
    run_and_edit(pc, f"G2 control before ({a.ctl_old})")
    t_swap = time.time()
    time.sleep(1)
    swap(T, a.tc_new)
    set_plugin(pc, a.ctl_new)
    out = run_and_edit(pc, f"G2 control after ({a.ctl_new})")
    old = cache_files_before(pc, t_swap)
    say(f"  control: compiler cache files from before the toolchain swap still in use: {old} "
        f"(klib core {'not recompiled' if lines(out, r'klib core: inputs unchanged') else 'recompiled'})")
    check("G2 control reproduces the report: compiler caches built by the old toolchain are reused after it changed in place",
          sum(old.values()) > 0, str(old))
    gradle(pc, "hotReloadDaemonStop")

    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
    say(f"## G2 with #98: {a.old_pin_plugin} on the old toolchain, then the same path with the new toolchain and {a.new_plugin}")
    swap(T, a.tc_old)
    p2 = project("g2", a.old_pin_plugin, T)
    run_and_edit(p2, f"G2 before ({a.old_pin_plugin})")
    rc, out, dt = gradle(p2, "hotReloadVerifyRelease", "-Pcmp.hotreload.releaseBaseline=true")
    check("G2: release baseline recorded on the old toolchain", rc == 0 and os.path.isfile(os.path.join(p2, "shared/build/hotreload/release/baseline.identity")), f"{dt:.0f}s")
    if rc != 0:
        tail(out)
    t_swap = time.time()
    time.sleep(1)
    swap(T, a.tc_new)
    set_plugin(p2, a.new_plugin)
    # an edit sent with hotReload alone (no package): refused, the running app's package is kept
    p_run = pid()
    edit(p2)
    rc, out, dt = gradle(p2, "hotReload")
    refused = lines(out, r"packaged with another build identity")
    for l in refused:
        say("  | " + l[:300])
    check("G2: hotReload alone after the toolchain change is refused, nothing cleared, the app keeps running",
          rc != 0 and bool(refused) and os.path.isfile(os.path.join(p2, "shared/build/hotreload/app/hot-symbols.txt")) and pid() == p_run,
          f"rc={rc} pid {p_run}->{pid()}")
    if not refused:
        tail(out)
    out = run_and_edit(p2, f"G2 after ({a.new_plugin})")
    ident = lines(out, r"build identity changed")
    check("G2: the toolchain change in place clears the old state with a reason",
          ident and "toolchainVersion" in ident[0] and "compilerJar" in ident[0], (ident or ["-"])[0][:300])
    check("G2: module klib recompiled", bool(lines(out, r"klib core: compiling")))
    old = cache_files_before(p2, t_swap)
    check("G2: no compiler cache file from before the swap (ic/, auto-cache/)", oldest_cache_mtime(p2) is not None and sum(old.values()) == 0, str(old))
    check("G2: the release baseline is kept", os.path.isfile(os.path.join(p2, "shared/build/hotreload/release/baseline.fingerprint")))
    rc, out, dt = gradle(p2, "hotReloadVerifyRelease")
    named = lines(out, r"recorded with another toolchain or plugin|the baseline was recorded with other")
    for l in named + lines(out, r"^\s+- "):
        say("  | " + l[:300])
    check("G2: verifying the old-toolchain baseline names the toolchain change instead of reporting a hot reload leak",
          bool(named) and "toolchainVersion" in named[0] and "compilerJar" in named[0], f"rc={rc} {dt:.0f}s")
    fresh_matches(p2, "G2")
    gradle(p2, "hotReloadDaemonStop")
except Exception as e:
    check(f"script error: {type(e).__name__}: {e}", False)
finally:
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
    say(f"stopped {BUNDLE}; app running: {pid() is not None}")
failed = [n for n, ok in results if not ok]
say(f"summary: {len(results) - len(failed)}/{len(results)} PASS" + ("" if not failed else f"; FAILED: {failed}"))
sys.exit(1 if failed else 0)
