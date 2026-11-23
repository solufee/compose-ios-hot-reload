#!/usr/bin/env python3
"""#120: Kotlin/Native unpacks the static libraries of a link into java.io.tmpdir/included<digits> (~120 MB with skiko) and
deletes them only at JVM exit; the compile daemons never exit. With #120 each daemon has a temp directory of its own and
removes what each request unpacked; the kotlinc-native fallback uses one that is removed when it exits.
  package -> run -> 10 HOT edits:
    - no new included* in $TMPDIR (only listed and counted; nothing in $TMPDIR is deleted here)
    - after each step, no included* left in the daemons' temp directories (<socket dir>/tmp-compile, tmp-klib)
    - no direct-* left in <workDir>/tmp
    - the daemon logs say how many included* directories each request removed
  hotReloadDaemonStop -> both daemon temp directories are gone
Run it with a plugin without #120 (e.g. 0.2.10) to see the old behaviour: the $TMPDIR check fails.
Run nothing else that links Kotlin/Native at the same time: it would add its own included* to $TMPDIR.
Usage: accept-included-tmp.py --zip WIZARD.zip --work DIR --plugin-repo URL --plugin-version V --toolchain DIST --udid U [--port N]
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import tempfile
import time
import zipfile

ap = argparse.ArgumentParser()
for k in ("zip", "work", "plugin-repo", "plugin-version", "toolchain", "udid"):
    ap.add_argument("--" + k, required=True)
ap.add_argument("--port", default="5568")
a = ap.parse_args()
WORK = os.path.abspath(a.work)
HOME = os.path.expanduser("~")
TMP = tempfile.gettempdir()
BUNDLE = "org.example.kmphr.hotreload"
results = []


def say(t=""):
    print(t.replace(WORK, "<WORK>").replace(TMP, "<TMPDIR>").replace(HOME, "<HOME>"), flush=True)


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    say(f"{'PASS' if ok else 'FAIL'} {name}" + (f" | {detail}" if detail else ""))


def included(d):
    try:
        return {n for n in os.listdir(d) if n.startswith("included") and os.path.isdir(os.path.join(d, n))}
    except FileNotFoundError:
        return set()


def mb(d):
    total = 0
    for root, _, files in os.walk(d):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total // (1024 * 1024)


def gradle(*args):
    t0 = time.time()
    r = subprocess.run([os.path.join(P, "gradlew"), "-p", P, *args, "--console=plain"], cwd=P, capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr, time.time() - t0


shutil.rmtree(WORK, ignore_errors=True)
os.makedirs(WORK)
with zipfile.ZipFile(a.zip) as z:
    z.extractall(WORK)
P = os.path.join(WORK, "KmpHr")
os.chmod(os.path.join(P, "gradlew"), 0o755)
s = open(os.path.join(P, "settings.gradle.kts")).read()
open(os.path.join(P, "settings.gradle.kts"), "w").write(s.replace(
    "pluginManagement {\n    repositories {\n",
    f'pluginManagement {{\n    repositories {{\n        exclusiveContent {{ forRepository {{ maven {{ url = uri("{a.plugin_repo}") }} }}; filter {{ includeGroupByRegex("com\\\\.solufee\\\\.cmp(\\\\..*)?") }} }}\n', 1))
b = open(os.path.join(P, "shared/build.gradle.kts")).read()
open(os.path.join(P, "shared/build.gradle.kts"), "w").write(
    b.replace("    alias(libs.plugins.composeCompiler)\n}", f'    alias(libs.plugins.composeCompiler)\n    id("com.solufee.cmp.hotreload") version "{a.plugin_version}"\n}}', 1))
open(os.path.join(P, "local.properties"), "w").write(
    f"cmp.hotreload.toolchainDir={a.toolchain}\ncmp.hotreload.simulatorUdid={a.udid}\ncmp.hotreload.port={a.port}\n")
APP = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr/App.kt")
WORKDIR = os.path.join(P, "shared/build/hotreload")


def daemon_tmp():
    """The daemons' temp directories, next to their sockets (the socket path is in each daemon log's READY line)."""
    dirs = []
    for kind in ("compile", "klib"):
        log = os.path.join(WORKDIR, f"daemon-{kind}.log")
        m = re.findall(r"^READY (\S+) pid=", open(log).read(), re.M) if os.path.isfile(log) else []
        if m:
            dirs.append(os.path.join(os.path.dirname(m[-1]), f"tmp-{kind}"))
    return dirs


DIRECT_TMP = os.path.join(WORKDIR, "tmp")

say(f"# #120 acceptance (included* temp directories), {time.strftime('%F %T')}, plugin {a.plugin_version}, simulator {a.udid}")
before = included(TMP)
say(f"included* in $TMPDIR before: {len(before)} (left by earlier runs; not touched)")
EDIT = [0]


def daemon_left():
    return {d: sorted(included(d)) for d in daemon_tmp() if included(d)}


def step(name, *args, hot=False):
    rc, out, dt = gradle(*args)
    left = daemon_left()
    direct = [n for n in (os.listdir(DIRECT_TMP) if os.path.isdir(DIRECT_TMP) else []) if n.startswith("direct-")]
    ok = rc == 0 and (not hot or "compat: HOT" in out)
    check(f"{name} ({dt:.1f}s)", ok, "" if ok else out[-400:])
    check(f"{name}: no included* left in the daemons' temp directories, no direct-* left", not left and not direct,
          f"left={left} direct={direct}")
    return out


try:
    step("package", "hotReloadPackage")
    step("run", "hotReloadRun")
    for i in range(10):
        EDIT[0] += 1
        src = open(APP).read()
        src2 = re.sub(r'Text\("Click me![^"]*"\)', f'Text("Click me! t{EDIT[0]}")', src, count=1)
        assert src2 != src
        open(APP, "w").write(src2)
        step(f"HOT {i + 1}", "hotReload", hot=True)
    new = sorted(included(TMP) - before)
    check("no new included* in $TMPDIR during package, run and 10 HOT", not new,
          f"{len(new)} new: {new[:5]}{' ...' if len(new) > 5 else ''} ({sum(mb(os.path.join(TMP, n)) for n in new)} MB)")
    removed = []
    for kind in ("compile", "klib"):
        log = os.path.join(P, "shared/build/hotreload", f"daemon-{kind}.log")
        if os.path.isfile(log):
            removed += [int(m) for m in re.findall(r"TMP removed (\d+) included dir", open(log).read())]
    say(f"  daemon logs: {len(removed)} request(s) removed included* directories, {sum(removed)} in total")
    tmps = daemon_tmp()
    existed = [d for d in tmps if os.path.isdir(d)]
    check("the daemons' temp directories exist while they run (beside their sockets)", len(existed) == 2, str(existed))
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
    rc, out, dt = gradle("hotReloadDaemonStop")
    gone = [d for d in tmps if os.path.exists(d)]
    check("hotReloadDaemonStop: both daemon temp directories are gone", rc == 0 and not gone, f"still there: {gone}")
except Exception as e:
    check(f"script error: {type(e).__name__}: {e}", False)
finally:
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, BUNDLE], capture_output=True)
    gradle("hotReloadDaemonStop")
failed = [n for n, ok in results if not ok]
say(f"summary: {len(results) - len(failed)}/{len(results)} PASS" + ("" if not failed else f"; FAILED: {failed}"))
