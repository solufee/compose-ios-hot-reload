#!/usr/bin/env python3
"""Manual hot-swap proof for the change kinds the classifier now lets through (cases.py, expect=hot).

Edits one module file of a running app step by step and runs `gradlew hotReload` after each step. Every
step must be classified HOT, be loaded by the app, and show its new value on screen (AXe label), with the
same PID. A helper `hrx()` is shown next to the module's mark so every new member is actually called.

Usage: manual_hot.py --project DIR --file REL --text-anchor 'Text("UI $UI_MARK"' --udid U --exe NAME [--gradlew PATH]
The app must already be running (gradlew hotReloadRun). The file is restored at the end.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

ap = argparse.ArgumentParser()
ap.add_argument("--project", required=True)
ap.add_argument("--file", required=True)
ap.add_argument("--text-anchor", required=True, help='start of the Text(...) call that shows the module mark')
ap.add_argument("--udid", required=True)
ap.add_argument("--exe", required=True)
ap.add_argument("--gradlew")
ap.add_argument("--prefix", default="", help="suffix for the added names, so two modules of one app do not clash")
a = ap.parse_args()
P = os.path.abspath(a.project)
F = os.path.join(P, a.file)
GRADLEW = a.gradlew or os.path.join(P, "gradlew")
X = a.prefix


def walk(ns):
    for n in ns:
        yield n
        yield from walk(n.get("children", []))


def labels():
    r = subprocess.run(["axe", "describe-ui", "--udid", a.udid], capture_output=True, text=True)
    d = json.loads(r.stdout) if r.returncode == 0 else []
    return [n.get("AXLabel") or "" for n in walk(d if isinstance(d, list) else [d])]


def wait_label(sub, timeout=30):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if any(sub in l for l in labels()):
            return True
        time.sleep(0.2)
    return False


def pid():
    o = subprocess.run(["pgrep", "-f", f"Devices/{a.udid}/.*/{a.exe}.app/{a.exe}"], capture_output=True, text=True).stdout.split()
    return o[0] if o else None


def hrx(expr):
    """Replace the body of the helper shown on screen."""
    return lambda s: re.sub(rf"fun hrx{X}\(\): String = .*", f"fun hrx{X}(): String = {expr}", s, count=1)


def sub(old, new):
    def f(s):
        if old not in s:
            raise KeyError(old)
        return s.replace(old, new, 1)
    return f


def chain(*fs):
    def f(s):
        for g in fs:
            s = g(s)
        return s
    return f


def append(text):
    return lambda s: s + text


anchor = a.text_anchor
STEPS = [
    # (kind proved, edit, text expected on screen)
    ("setup: helper shown next to the mark", chain(sub(anchor + '"', anchor + f' |${{hrx{X}()}}|"'), append(f'\nfun hrx{X}(): String = "x0"\n')), "|x0|"),
    ("new final class", chain(append(f"\nclass Calc{X}(val a: Int) {{\n    fun twice(): Int = a * 2\n}}\n"), hrx(f'"c" + Calc{X}(5).twice()')), "|c10|"),
    ("method added to a final class", chain(sub("    fun twice(): Int = a * 2\n", "    fun twice(): Int = a * 2\n    fun thrice(): Int = a * 3\n"), hrx(f'"c" + Calc{X}(5).thrice()')), "|c15|"),
    ("body of the method added by the previous reload", sub("fun thrice(): Int = a * 3", "fun thrice(): Int = a * 4"), "|c20|"),
    ("new object", chain(append(f'\nobject Fmt{X} {{\n    fun a(): String = "oa"\n}}\n'), hrx(f"Fmt{X}.a()")), "|oa|"),
    ("method added to an object", chain(sub('    fun a(): String = "oa"\n', '    fun a(): String = "oa"\n    fun b(): String = "ob"\n'), hrx(f"Fmt{X}.b()")), "|ob|"),
    ("new class with a companion", chain(append(f'\nclass Box{X} {{\n    companion object {{\n        fun one(): String = "c1"\n    }}\n}}\n'), hrx(f"Box{X}.one()")), "|c1|"),
    ("method added to a companion object", chain(sub('        fun one(): String = "c1"\n', '        fun one(): String = "c1"\n        fun two(): String = "c2"\n'), hrx(f"Box{X}.two()")), "|c2|"),
    ("new typealias", chain(append(f'\ntypealias Label{X} = String\n\nfun lab{X}(): Label{X} = "alias"\n'), hrx(f"lab{X}()")), "|alias|"),
    ("new internal function", chain(append(f'\ninternal fun vis{X}(): String = "vint"\n'), hrx(f"vis{X}()")), "|vint|"),
    ("internal -> public (and body)", sub(f'internal fun vis{X}(): String = "vint"', f'fun vis{X}(): String = "vpub"'), "|vpub|"),
    # 0.2.4 (#28): signature changes; every caller (here hrx) is recompiled into the patch.
    ("top-level: parameter with a default added", chain(sub(f'fun lab{X}(): Label{X} = "alias"', f'fun lab{X}(s: String = "2"): Label{X} = "alias" + s'), hrx(f"lab{X}()")), "|alias2|"),
    ("top-level: parameter removed", sub(f'fun lab{X}(s: String = "2"): Label{X} = "alias" + s', f'fun lab{X}(): Label{X} = "al3"'), "|al3|"),
    ("top-level: return type changed", chain(sub(f'fun lab{X}(): Label{X} = "al3"', f'fun lab{X}(): Int = 4'), hrx(f'"r" + lab{X}()')), "|r4|"),
    ("final member: parameter with a default added", chain(sub("fun thrice(): Int = a * 4", "fun thrice(k: Int = 6): Int = a * k"), hrx(f'"c" + Calc{X}(5).thrice()')), "|c30|"),
    ("object member: return type changed", chain(sub('    fun b(): String = "ob"\n', '    fun b(): Int = 7\n'), hrx(f'"o" + Fmt{X}.b()')), "|o7|"),
    ("companion member: parameter added (no default), caller changed", chain(sub('        fun two(): String = "c2"\n', '        fun two(n: Int): String = "c$n"\n'), hrx(f"Box{X}.two(9)")), "|c9|"),
    ("final member removed", chain(sub("    fun thrice(k: Int = 6): Int = a * k\n", ""), hrx(f'"c" + Calc{X}(5).twice()')), "|c10|"),
    # 0.2.4 (#20): relaxed cases and already-hot cases now covered.
    ("const val added", chain(append(f'\nconst val K{X} = "k1"\n'), hrx(f"K{X}")), "|k1|"),
    ("inline method added to a final class", chain(sub("    fun twice(): Int = a * 2\n", "    fun twice(): Int = a * 2\n    inline fun quad(): Int = a * 4\n"), hrx(f'"q" + Calc{X}(5).quad()')), "|q20|"),
    ("suspend method added to an object", chain(sub('    fun a(): String = "oa"\n', '    fun a(): String = "oa"\n    suspend fun later(): String = "os"\n'), hrx(f"kotlinx.coroutines.runBlocking {{ Fmt{X}.later() }}")), "|os|"),
    ("new open class with a subclass", chain(append(f'\nopen class Shape{X} {{\n    open fun sides(): Int = 0\n}}\n\nclass Tri{X} : Shape{X}() {{\n    override fun sides(): Int = 3\n}}\n'), hrx(f'"s" + Tri{X}().sides()')), "|s3|"),
    ("final method added to an open class, called on a subclass instance", chain(sub("    open fun sides(): Int = 0\n", "    open fun sides(): Int = 0\n    fun twiceSides(): Int = sides() * 2\n"), hrx(f'"t" + Tri{X}().twiceSides()')), "|t6|"),
    ("new sealed class with a subclass", chain(append(f'\nsealed class Pick{X}\nclass PickA{X} : Pick{X}()\n\nfun pickName{X}(p: Pick{X}): String = when (p) {{\n    is PickA{X} -> "pa"\n}}\n'), hrx(f"pickName{X}(PickA{X}())")), "|pa|"),
    ("subclass added to a sealed class (exhaustive when updated)", chain(sub(f"class PickA{X} : Pick{X}()\n", f"class PickA{X} : Pick{X}()\nclass PickB{X} : Pick{X}()\n"), sub(f'    is PickA{X} -> "pa"\n', f'    is PickA{X} -> "pa"\n    is PickB{X} -> "pb"\n'), hrx(f"pickName{X}(PickB{X}())")), "|pb|"),
    ("companion object added to an existing class", chain(sub(f"class Calc{X}(val a: Int) {{\n", f"class Calc{X}(val a: Int) {{\n    companion object {{\n        fun seven(): Calc{X} = Calc{X}(7)\n    }}\n"), hrx(f'"m" + Calc{X}.seven().twice()')), "|m14|"),
]

orig = open(F).read()
src = orig
p0 = pid()
failed = 0
print(f"# manual hot-swap proof: {a.file} in {os.path.basename(P)}, app pid {p0}", flush=True)
try:
    for i, (kind, edit, expect) in enumerate(STEPS):
        src = edit(src)
        open(F, "w").write(src)
        t0 = time.time()
        r = subprocess.run([GRADLEW, "-p", P, "hotReload", "--console=plain"], capture_output=True, text=True)
        out = r.stdout + r.stderr
        hot = "compat: HOT" in out
        loaded = "app loaded the patch" in out
        seen = wait_label(expect)
        p1 = pid()
        ok = r.returncode == 0 and hot and loaded and seen and p1 == p0
        failed += not ok
        warm = "; ".join(l for l in out.splitlines() if l.startswith("WARM "))
        print(f"step{i:02d} {'OK' if ok else 'FAIL'} {(time.time()-t0)*1000:.0f}ms hot={hot} loaded={loaded} on_screen={seen} "
              f"pid {p0}->{p1} [{kind}] expect {expect!r}" + (f" | {warm}" if warm else "") +
              ("" if ok else " | " + out[-400:].replace("\n", " | ")), flush=True)
finally:
    open(F, "w").write(orig)
    print("# file restored", flush=True)
print(f"summary: {len(STEPS) - failed}/{len(STEPS)} hot and on screen, same pid")
sys.exit(1 if failed else 0)
