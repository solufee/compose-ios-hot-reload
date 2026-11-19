#!/usr/bin/env python3
"""Phase 2 compile-time measurements.

Factors:
  mode = full | cacheonly          (KONAN_HOT_RELOAD_CACHE_ONLY)
  ic   = on | off                  (-Xenable-incremental-compilation)

For each run: change BUILD_TAG, run the compiler (same args as reload-a.sh),
record wall time and the .a produced. Emits a table + p50.
"""
import os
import re
import statistics
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
MAIN = os.path.join(HERE, "main.kt")
KC = "$KOTLIN_DIST/bin/kotlinc-native"
PLUGIN = os.environ.get("COMPOSE_PLUGIN_JAR", os.path.expanduser("~/konan-hot-reload/compose-plugin/compiler-2.5.255-SNAPSHOT-shadow.jar"))
KLIBLIST = os.path.join(HERE, "klibs-jbmain4.txt")
AUTO_CACHE = os.path.join(HERE, "auto-cache-jbmain27")
IC_DIR = os.path.join(HERE, "ic-jbmain27")

env = dict(os.environ)
env["JAVA_HOME"] = os.path.expanduser(
    "~/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.21+9/Contents/Home")

libs = []
for line in open(KLIBLIST):
    line = line.strip()
    if "/.gradle/caches/" in line:
        libs += ["-l", line]

BASE = ["-target", "ios_simulator_arm64", "-p", "program", "-g",
        "-Xcompilation-scheme=split",
        "-Xbinary=perFileCacheForStdlib=false",
        "-Xauto-cache-from=" + os.path.expanduser("~/.gradle/caches"),
        "-Xauto-cache-dir=" + AUTO_CACHE,
        "-Xplugin=" + PLUGIN,
        "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
        "-linker-option=-framework", "-linker-option=ImageIO",
        "-linker-option=-Wl,-no_warn_duplicate_libraries"]


def set_tag(tag):
    s = open(MAIN).read()
    s2 = re.sub(r'private const val BUILD_TAG = "[^"]*"',
                f'private const val BUILD_TAG = "{tag}"', s)
    assert s2 != s
    open(MAIN, "w").write(s2)


def run(mode, ic, tag, extra=()):
    set_tag(tag)
    args = list(BASE)
    if ic:
        args += ["-Xenable-incremental-compilation", "-Xic-cache-dir=" + IC_DIR]
    args += list(extra) + libs + [os.path.join(HERE, "main.kt"), "-o",
                                 os.path.join(HERE, "ComposeHR-patch.kexe")]
    e = dict(env)
    if mode == "cacheonly":
        e["KONAN_HOT_RELOAD_CACHE_ONLY"] = "1"
    t0 = time.time()
    p = subprocess.run([KC] + args, env=e, capture_output=True, text=True)
    dt = time.time() - t0
    if p.returncode != 0:
        print(p.stdout[-2000:], p.stderr[-2000:])
        raise SystemExit("compile failed")
    return dt


def pct(v, q):
    v = sorted(v)
    if not v:
        return 0
    k = (len(v) - 1) * q
    lo, hi = int(k), min(int(k) + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


N = int(sys.argv[1]) if len(sys.argv) > 1 else 5
which = sys.argv[2].split(",") if len(sys.argv) > 2 else ["cacheonly", "full"]

results = {}
n = 1000
for mode in which:
    for ic in (True, False):
        key = f"{mode}-ic-{'on' if ic else 'off'}"
        vals = []
        for i in range(N):
            n += 1
            dt = run(mode, ic, f"m{n}")
            vals.append(dt)
            print(f"{key}[{i}] {dt*1000:.0f} ms", flush=True)
        results[key] = vals

print("\n=== summary ===")
for key, vals in results.items():
    print(f"{key}: n={len(vals)} p50={pct(vals,.5)*1000:.0f}ms "
          f"p90={pct(vals,.9)*1000:.0f}ms min={min(vals)*1000:.0f}ms")
