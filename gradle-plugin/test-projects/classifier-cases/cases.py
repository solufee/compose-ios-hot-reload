#!/usr/bin/env python3
"""Classifier case table: what compat-classify.py says for one kind of change at a time.

For every case, a base source and a variant are compiled to real klibs (kotlinc-native with the Compose
compiler plugin, so the declarations Compose generates are in the klib too), snapshotted, and classified.
The table shows the verdict next to the expected one:

  expect=hot   the change is safe to hot-swap (proved by a manual hot reload where marked "manual")
  expect=warm  the change must keep forcing a warm restart (counter-examples)

Usage: cases.py --toolchain <kotlin-native dist with toolchain.json> [--classifier scripts/compat-classify.py]
                [--only NAME ...] [--jobs 4] [--out DIR]
Exit 1 when any verdict differs from the expectation.
"""
import argparse
import concurrent.futures
import json
import os
import shutil
import subprocess
import sys
import tempfile

BASE = '''package cases

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import platform.darwin.NSObject

const val MARK = "base"

val topProp: Int = 1

@Composable
fun Screen(tick: Int) {
    Text("tick $tick $MARK")
    Button(onClick = { }) { Text("one") }
}

@Composable
fun Other() {
    Text("other")
}

class Foo(val a: Int) {
    fun f(): Int = a + 1
    private fun hidden(): Int = a
    @Composable
    fun Show(t: Int) { Text("show $t") }
}

fun label(n: Int): String = "n$n"

@Composable
private fun Row2(t: Int) {
    Text("row $t")
}

open class Base {
    open fun g(): Int = 1
}

interface Iface {
    fun h(): Int
}

object Obj {
    fun o(): Int = 1
}

class WithCompanion {
    companion object {
        fun c(): Int = 1
    }
}

data class Data(val x: Int)

enum class Color { RED, GREEN }

class Outer {
    class Inner(val i: Int)
    data class Pair2(val p: Int)
}

inline fun twice(x: Int): Int = x * 2

suspend fun later(x: Int): Int = x

internal fun helper(): Int = 1

private fun privTop(): Int = 1

abstract class Shape {
    abstract fun area(): Int
}

class Holder : NSObject() {
    fun held(): Int = 1
}

enum class Mode { ON, OFF; fun label(): String = name }

typealias Size = Int

fun area(s: Size): Size = s * s
'''


def sub(old, new):
    return lambda s: s.replace(old, new, 1) if old in s else (_ for _ in ()).throw(KeyError(old))


def add(text):
    return lambda s: s + text


# Composable lambdas live as fields of the compiler-generated `ComposableSingletons$<File>Kt` object, which is
# not in the klib metadata. Adding/removing them is hot on purpose: on every reload the runtime swaps in the new
# TypeInfo, migrates old instances (same-name fields copied, new ones null, references rewritten) and the patch
# brings its own `$instance` slot, so the object is re-constructed with the new layout (runtime
# hot_reload/impl/cpp/HotReload.cpp ReloadClassesAndInstances, StateTransfer.cpp). Proved on a running app by
# lambda_stress.py (add/remove/re-add, 20 reloads with taps, one PID).
#
# (name, expect, edit, note[, from]) - "from" names another case: its variant is the old side and this
# case's edit is applied to it (two reloads in a row).
CASES = [
    # ---- should be hot (some already are) ----
    ("body-change", "hot", sub("fun f(): Int = a + 1", "fun f(): Int = a + 2"), "plain function body"),
    ("const-change", "hot", sub('const val MARK = "base"', 'const val MARK = "next"'), "const value"),
    ("add-class", "hot", add("\nclass Fresh(val z: Int)\n"), "new class"),
    ("add-private-method", "hot", sub("    private fun hidden(): Int = a\n", "    private fun hidden(): Int = a\n    private fun hidden2(): Int = a * 3\n"), "private method"),
    ("add-private-top-fun", "hot", add("\nprivate fun privTop2(): Int = 2\n"), "private top-level function"),
    ("add-top-fun", "hot", add("\nfun extra(): Int = 3\n"), "public top-level function"),
    ("add-composable", "hot", add("\n@Composable\nfun Extra() { Text(\"extra\") }\n"), "new composable function"),
    ("add-composable-lambda", "hot", sub('    Button(onClick = { }) { Text("one") }\n', '    Button(onClick = { }) { Text("one") }\n    Button(onClick = { }) { Text("two") }\n'), "new composable lambda in an existing composable (ComposableSingletons)"),
    ("remove-composable-lambda", "hot", sub('    Button(onClick = { }) { Text("one") }\n', ''), "composable lambda removed"),
    ("remove-one-of-two-lambdas", "hot", sub('    Button(onClick = { }) { Text("one") }\n', '    Button(onClick = { }) { Text("one") }\n    Button(onClick = { }) { Text("two") }\n'), "step 1: a second lambda (step 2 removes the first)"),
    ("remove-first-lambda-keep-second", "hot", sub('    Button(onClick = { }) { Text("one") }\n', ''), "lambda removed while another remains: the remaining field moves", "remove-one-of-two-lambdas"),
    ("lambda-in-other-fun", "hot", sub('    Text("other")\n', '    Text("other")\n    Button(onClick = { }) { Text("b") }\n'), "first lambda in a composable that had none"),
    ("add-method-final-class", "hot", sub("    fun f(): Int = a + 1\n", "    fun f(): Int = a + 1\n    fun f2(): Int = a + 5\n"), "public method on a final class (no vtable)"),
    ("add-method-object", "hot", sub("    fun o(): Int = 1\n", "    fun o(): Int = 1\n    fun o2(): Int = 2\n"), "method on an object"),
    ("add-method-companion", "hot", sub("        fun c(): Int = 1\n", "        fun c(): Int = 1\n        fun c2(): Int = 2\n"), "method on a companion object"),
    ("add-extension-fun", "hot", add("\nfun Foo.ext(): Int = a * 7\n"), "extension function"),
    ("add-typealias", "hot", add("\ntypealias Count = Int\n"), "typealias"),
    ("add-nested-class", "hot", sub("    class Inner(val i: Int)\n", "    class Inner(val i: Int)\n    class Inner2(val k: Int)\n"), "new nested class"),
    # #28: a changed signature of a top-level function or a final member of a final class/object is hot (every
    # caller is recompiled into the patch); a @Composable needs forceRecompose (cases run with it unless in
    # FORCE_OFF) and prints NOTE remember-reset.
    ("sig-member-default-param-added", "hot", sub("fun f(): Int = a + 1", "fun f(x: Int = 0): Int = a + 1"), "final member: parameter with a default added"),
    ("sig-member-return-changed", "hot", sub("fun f(): Int = a + 1", "fun f(): Long = a + 1L"), "final member: return type"),
    ("sig-member-removed", "hot", sub("    fun f(): Int = a + 1\n", ""), "final member removed (its callers changed too)"),
    ("sig-object-member", "hot", sub("    fun o(): Int = 1\n", "    fun o(k: Int = 0): Int = 1 + k\n"), "object member: parameter added"),
    ("sig-companion-member", "hot", sub("        fun c(): Int = 1\n", "        fun c(k: String): Int = k.length\n"), "companion member: parameter added (no default)"),
    ("sig-top-param-added", "hot", sub('fun label(n: Int): String = "n$n"', 'fun label(n: Int, suffix: String = "!"): String = "n$n$suffix"'), "top-level: parameter with a default added"),
    ("sig-top-param-removed", "hot", sub('fun label(n: Int): String = "n$n"', 'fun label(): String = "n"'), "top-level: parameter removed"),
    ("sig-top-param-type", "hot", sub('fun label(n: Int): String = "n$n"', 'fun label(n: Long): String = "n$n"'), "top-level: parameter type"),
    ("sig-top-return-changed", "hot", sub('fun label(n: Int): String = "n$n"', 'fun label(n: Int): Int = n'), "top-level: return type"),
    ("sig-top-overload-added", "hot", add('\nfun label(n: String): String = n\n'), "top-level: overload added"),
    ("sig-composable-param-added", "hot", sub("fun Screen(tick: Int) {", 'fun Screen(tick: Int, rim: String = "R") {'), "@Composable, forceRecompose on: parameter added (NOTE remember-reset)"),
    ("sig-composable-param-removed", "hot", sub("fun Screen(tick: Int) {\n    Text(\"tick $tick $MARK\")", "fun Screen() {\n    Text(\"tick $MARK\")"), "@Composable, forceRecompose on: parameter removed"),
    ("sig-composable-member", "hot", sub('    fun Show(t: Int) { Text("show $t") }', '    fun Show(t: Int, u: Int = 2) { Text("show $t $u") }'), "@Composable member of a final class, forceRecompose on"),
    ("sig-private-composable", "hot", sub("private fun Row2(t: Int) {", 'private fun Row2(t: Int, s: String = "") {'), "private @Composable, forceRecompose on"),
    ("sig-composable-annotation-added", "hot", sub("internal fun helper(): Int = 1", "@Composable\ninternal fun helper(): Int = 1"), "@Composable added to an existing function, forceRecompose on (NOTE remember-reset)"),
    ("sig-default-value-changed", "hot", sub("fun f(): Int = a + 1", "fun f(x: Int = 0): Int = a + 1 + x"), "step 1: a parameter with a default (step 2 changes only the default value)"),
    ("default-value-only", "hot", sub("fun f(x: Int = 0): Int", "fun f(x: Int = 5): Int"), "default value changed (no signature change: the $default body only)", "sig-default-value-changed"),
    # #20 (RELAXATIONS.md): relaxed in 0.2.4 (hot), already hot and now covered, and the tightened @ExportObjCClass.
    ("relax-const-added", "hot", add('\nconst val MARK2 = "two"\n'), "const val added (no storage to initialize)"),
    ("relax-const-removed", "hot", sub('\nconst val MARK2 = "two"\n', ''), "const val removed (step 2 of relax-const-added)", "relax-const-added"),
    ("relax-inline-method-final-class", "hot", sub("    fun f(): Int = a + 1\n", "    fun f(): Int = a + 1\n    inline fun f4(): Int = a + 4\n"), "inline method added to a final class"),
    ("relax-suspend-method-final-class", "hot", sub("    fun f(): Int = a + 1\n", "    fun f(): Int = a + 1\n    suspend fun f5(): Int = a + 5\n"), "suspend method added to a final class"),
    ("relax-suspend-method-object", "hot", sub("    fun o(): Int = 1\n", "    fun o(): Int = 1\n    suspend fun o5(): Int = 5\n"), "suspend method added to an object"),
    ("relax-final-method-open-class", "hot", sub("    open fun g(): Int = 1\n", "    open fun g(): Int = 1\n    fun g3(): Int = 3\n"), "final method added to an open class (direct call)"),
    ("abstract-class-method-added", "hot", sub("    abstract fun area(): Int\n", "    abstract fun area(): Int\n    fun perimeter(): Int = 0\n"), "final method added to an abstract class (#20: direct call; was warm)"),
    ("covered-new-object-with-property", "hot", add('\nobject Settings2 {\n    val limit: Int = 3\n}\n'), "new object with a property"),
    ("covered-sealed-class", "hot", sub("enum class Mode", "sealed class Pick\nclass PickA : Pick()\n\nenum class Mode"), "step 1: a sealed class with one subclass"),
    ("covered-sealed-subclass-added", "hot", sub("class PickA : Pick()\n", "class PickA : Pick()\nclass PickB : Pick()\n"), "subclass added to a sealed class", "covered-sealed-class"),
    ("covered-companion-added", "hot", sub("    class Inner(val i: Int)\n", "    class Inner(val i: Int) {\n        companion object {\n            fun mk(): Inner = Inner(1)\n        }\n    }\n"), "companion object added to an existing class"),
    ("visibility-internal-to-public", "hot", sub("internal fun helper()", "fun helper()"), "top-level function visibility only"),
    ("edit-new-method-body", "hot", sub("    fun f2(): Int = a + 5\n", "    fun f2(): Int = a + 6\n"), "body of a method added by the previous reload (step 2 of add-method-final-class)", "add-method-final-class"),
    ("add-private-method-object", "hot", sub("    fun o(): Int = 1\n", "    fun o(): Int = 1\n    private fun o3(): Int = 3\n"), "private method on an object"),
    # ---- must stay warm ----
    ("sig-composable-force-off", "warm", sub("fun Screen(tick: Int) {", 'fun Screen(tick: Int, rim: String = "R") {'), "@Composable with forceRecompose off: the restart lambda keeps the old parameters"),
    ("sig-private-composable-force-off", "warm", sub("private fun Row2(t: Int) {", 'private fun Row2(t: Int, s: String = "") {'), "private @Composable with forceRecompose off"),
    ("sig-composable-member-force-off", "warm", sub('    fun Show(t: Int) { Text("show $t") }', '    fun Show(t: Int, u: Int = 2) { Text("show $t $u") }'), "@Composable member with forceRecompose off"),
    ("sig-composable-annotation-force-off", "warm", sub("internal fun helper(): Int = 1", "@Composable\ninternal fun helper(): Int = 1"), "@Composable added to an existing function, forceRecompose off"),
    ("counter-val-added-existing-file", "warm", add('\nval limit2: Int = 2\n'), "#20 counter: a val (not const) added to an existing file"),
    ("counter-open-method-added", "warm", sub("    open fun g(): Int = 1\n", "    open fun g(): Int = 1\n    open fun g4(): Int = 4\n"), "#20 counter: an open method added to an open class (vtable)"),
    ("counter-abstract-method-added", "warm", sub("    abstract fun area(): Int\n", "    abstract fun area(): Int\n    abstract fun side(): Int\n"), "#20 counter: an abstract method added (vtable)"),
    ("counter-inline-body-of-existing", "warm", sub("inline fun twice(x: Int): Int = x * 2", "inline fun twice(x: Int): Int = x * 4"), "#20 counter: body of an existing inline function"),
    ("counter-suspend-body-of-existing", "warm", sub("suspend fun later(x: Int): Int = x", "suspend fun later(x: Int): Int = x * 2"), "#20 counter: body of an existing suspend function"),
    ("counter-final-method-enum", "warm", sub("fun label(): String = name }", "fun label(): String = name\n    fun label2(): String = name }"), "#20 counter: final method added to an enum class"),
    ("covered-export-objc-added", "hot", sub("class Holder : NSObject() {", "@kotlinx.cinterop.ExportObjCClass\nclass Holder : NSObject() {"), "#20: @ExportObjCClass added - not recorded in the klib metadata, so not visible to the classifier (RELAXATIONS.md 6b)"),
    ("sig-inline", "warm", sub("inline fun twice(x: Int): Int = x * 2", "inline fun twice(x: Int, y: Int = 2): Int = x * y"), "inline function signature (callers inline the body)"),
    ("sig-suspend", "warm", sub("suspend fun later(x: Int): Int = x", "suspend fun later(x: Int, y: Int = 0): Int = x + y"), "suspend function signature (continuations)"),
    ("sig-open-member", "warm", sub("    open fun g(): Int = 1\n", "    open fun g(k: Int = 0): Int = 1 + k\n"), "open member signature (vtable)"),
    ("sig-interface-member", "warm", sub("    fun h(): Int\n", "    fun h(k: Int = 0): Int\n"), "interface member signature (itable)"),
    ("sig-abstract-class-member", "warm", sub("    abstract fun area(): Int\n", "    abstract fun area(scale: Int = 1): Int\n"), "abstract member signature"),
    ("sig-objc-subclass-member", "warm", sub("    fun held(): Int = 1\n", "    fun held(k: Int = 0): Int = 1 + k\n"), "member of a Kotlin subclass of an ObjC class (selector)"),
    ("sig-enum-member", "warm", sub("fun label(): String = name }", "fun label(p: String = \"\"): String = p + name }"), "member of an enum class"),
    ("sig-constructor", "warm", sub("class Foo(val a: Int) {", "class Foo(val a: Int, unused: String = \"\") {"), "constructor parameter added (constructors stay warm)"),
    ("field-added", "warm", sub("class Foo(val a: Int) {", "class Foo(val a: Int, val b: Int = 0) {"), "field layout"),
    ("inline-body", "warm", sub("inline fun twice(x: Int): Int = x * 2", "inline fun twice(x: Int): Int = x * 3"), "inline body"),
    ("suspend-body", "warm", sub("suspend fun later(x: Int): Int = x", "suspend fun later(x: Int): Int = x + 1"), "suspend body"),
    ("top-property-added", "warm", add("\nval topProp2: Int = 2\n"), "new top-level property"),
    ("supertype-changed", "warm", sub("class Foo(val a: Int) {", "class Foo(val a: Int) : Iface {\n    override fun h(): Int = 0"), "class gains an interface"),
    ("data-ctor-changed", "warm", sub("data class Data(val x: Int)", "data class Data(val x: Int, val y: Int = 0)"), "data class primary constructor"),
    ("enum-entry-added", "warm", sub("enum class Color { RED, GREEN }", "enum class Color { RED, GREEN, BLUE }"), "enum entry"),
    ("open-class-method-added", "warm", sub("    open fun g(): Int = 1\n", "    open fun g(): Int = 1\n    open fun g2(): Int = 2\n"), "virtual method on an open class (vtable)"),
    ("interface-method-added", "warm", sub("    fun h(): Int\n", "    fun h(): Int\n    fun h2(): Int = 2\n"), "interface method (itable)"),
    ("companion-field-added", "warm", sub("        fun c(): Int = 1\n", "        val counter: Int = 7\n        fun c(): Int = 1\n"), "field added to a companion object"),
    ("nested-data-ctor-changed", "warm", sub("    data class Pair2(val p: Int)\n", "    data class Pair2(val p: Int, val q: Int = 0)\n"), "nested data class primary constructor"),
    ("nested-field-added", "warm", sub("    class Inner(val i: Int)\n", "    class Inner(val i: Int, val j: Int = 0)\n"), "field layout of a nested class"),
    ("class-removed", "warm", sub("data class Data(val x: Int)\n", ""), "class removed"),
    ("modality-final-to-open", "warm", sub("class Foo(val a: Int) {", "open class Foo(val a: Int) {"), "final class made open (subclasses could override formerly direct calls)"),
    ("override-added-final-class", "warm", sub("class WithCompanion {", "class WithCompanion {\n    override fun toString(): String = \"wc\""), "override added in a final class (vtable entry changes)"),
    ("typealias-changed", "warm", sub("typealias Size = Int", "typealias Size = Long"), "existing typealias retargeted (functions using it change ABI)"),
    ("objc-subclass-method-added", "warm", sub("    fun held(): Int = 1\n", "    fun held(): Int = 1\n    fun held2(): Int = 2\n"), "method on a Kotlin subclass of an ObjC class (selector registration)"),
    ("enum-method-added", "warm", sub("fun label(): String = name }", "fun label(): String = name\n    fun short(): String = name.take(1) }"), "method on an enum class"),
    ("property-no-field-added", "warm", sub("    fun f(): Int = a + 1\n", "    val doubled: Int get() = a * 2\n    fun f(): Int = a + 1\n"), "property without a backing field on a final class"),
    ("visibility-private-to-internal", "warm", sub("private fun privTop()", "internal fun privTop()"), "private top-level function made internal (symbol name changes)"),
    ("visibility-internal-to-private", "warm", sub("internal fun helper()", "private fun helper()"), "internal top-level function made private"),
    ("typealias-removed", "warm", sub("typealias Size = Int\n\nfun area(s: Size): Size = s * s", "fun area(s: Int): Int = s * s"), "typealias removed"),
    ("modality-open-to-abstract", "warm", sub("open class Base {", "abstract class Base {"), "open class made abstract"),
    ("modality-abstract-to-open", "warm", sub("abstract class Shape {\n    abstract fun area(): Int", "open class Shape {\n    open fun area(): Int = 0"), "abstract class made open"),
    ("modality-open-to-final", "warm", sub("open class Base {\n    open fun g(): Int = 1", "class Base {\n    fun g(): Int = 1"), "open class made final"),
]


# Cases classified as for an app running with forceRecompose off (all others: on, the plugin's default).
FORCE_OFF = {"sig-composable-force-off", "sig-composable-annotation-force-off", "sig-private-composable-force-off", "sig-composable-member-force-off"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--toolchain", required=True)
    ap.add_argument("--classifier", default=os.path.join(os.path.dirname(__file__), "../../../scripts/compat-classify.py"))
    ap.add_argument("--only", nargs="*")
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--out")
    a = ap.parse_args()

    tc = json.load(open(os.path.join(a.toolchain, "toolchain.json")))
    kotlinc = tc["kotlin"]["kotlincNative"]
    plugin = tc["compose"]["pluginJar"]
    dist_klib = os.path.realpath(os.path.join(a.toolchain, "klib")) + "/"
    klibs = [l.strip() for l in open(tc["compose"]["klibList"]) if l.strip() and not l.startswith("#")]
    klibs = [k for k in klibs if not os.path.realpath(k).startswith(dist_klib)]
    klib_tool = os.path.join(a.toolchain, "bin", "klib")
    work = a.out or tempfile.mkdtemp(prefix="classifier-cases-")
    os.makedirs(work, exist_ok=True)

    def build(name, source):
        d = os.path.join(work, name)
        shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d)
        src = os.path.join(d, "cases.kt")
        open(src, "w").write(source)
        args = [kotlinc, "-target", "ios_simulator_arm64", "-p", "library", "-g", f"-Xplugin={plugin}",
                "-opt-in=kotlinx.cinterop.ExperimentalForeignApi"]
        for k in klibs:
            args += ["-l", k]
        args += [src, "-o", os.path.join(d, "cases")]
        r = subprocess.run(args, capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(f"{name}: compile failed\n{r.stderr[-1500:]}")
        snap = os.path.join(d, "snap.json")
        r = subprocess.run(["python3", "-I", a.classifier, "snapshot", "--klib", os.path.join(d, "cases.klib"),
                            "--out", snap, "--klib-tool", klib_tool], capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(f"{name}: snapshot failed\n{r.stdout[-800:]}{r.stderr[-800:]}")
        return snap

    cases = [c for c in CASES if not a.only or c[0] in a.only or any(len(x) > 4 and x[4] == c[0] for x in CASES if x[0] in a.only)]
    sources = {"_base": BASE}
    for c in cases:
        name, _, edit, _ = c[:4]
        frm = c[4] if len(c) > 4 else None
        sources[name] = edit(sources[frm] if frm else BASE)
    with concurrent.futures.ThreadPoolExecutor(a.jobs) as ex:
        snaps = dict(zip(sources, ex.map(lambda kv: build(*kv), sources.items())))

    failed = 0
    print(f"{'case':30} {'expect':6} {'got':6} {'':4} reasons")
    for c in cases:
        name, expect, _, note = c[:4]
        frm = c[4] if len(c) > 4 else "_base"
        force = [] if name in FORCE_OFF else ["--force-recompose"]
        r = subprocess.run(["python3", "-I", a.classifier, "classify", "--old", snaps[frm], "--new", snaps[name], *force],
                           capture_output=True, text=True)
        got = {0: "hot", 3: "warm"}.get(r.returncode, f"err{r.returncode}")
        ok = got == expect
        failed += not ok
        reasons = "; ".join(l for l in r.stdout.splitlines() if l.startswith(("WARM ", "NOTE "))) or "-"
        print(f"{name:30} {expect:6} {got:6} {'OK' if ok else 'DIFF':4} {reasons[:230]}  [{note}]")
    print(f"summary: {len(cases) - failed}/{len(cases)} as expected")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
