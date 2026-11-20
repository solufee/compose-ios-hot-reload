package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 0.2.0 must-pass rule: adding or removing an expect/actual pair is a warm restart; edits that keep the set of
 * pairs are left to the classifier (bodies hot, signatures warm through compat-classify.py).
 * Each case is a module with commonMain + iosMain before and after the edit; the table lists the reasons expected.
 * The same rule is proved on a simulator by test-projects/kmp-template/accept-kmp.py.
 */
class ExpectActualCasesTest {
    @TempDir
    lateinit var tmp: File

    private val common0 = "package k\n\nexpect fun platformTag(): String\n\nfun dateLine(): String = \"d1\"\n"
    private val ios0 = "package k\n\nactual fun platformTag(): String = \"tag-1\"\n"

    private data class Case(val name: String, val common: String, val ios: String, val expect: List<String>, val extraFile: Pair<String, String>? = null)

    private val cases = listOf(
        // positive: a pair added or removed
        Case("add an expect/actual fun pair", common0 + "expect fun flavor(): String\n", ios0 + "actual fun flavor(): String = \"sim\"\n",
            listOf("WARM expect-actual flavor: expect fun added")),
        Case("remove the only pair", "package k\n\nfun dateLine(): String = \"d1\"\n", "package k\n",
            listOf("WARM expect-actual platformTag: expect fun removed")),
        Case("add an expect class pair", common0 + "expect class Clock() { fun now(): Long }\n", ios0 + "actual class Clock actual constructor() { actual fun now(): Long = 0 }\n",
            listOf("WARM expect-actual Clock: expect class added")),
        Case("add an expect val pair", common0 + "internal expect val tag: String\n", ios0 + "internal actual val tag: String = \"t\"\n",
            listOf("WARM expect-actual tag: expect val added")),
        Case("add an annotated expect object pair", common0 + "@OptIn(ExperimentalStdlibApi::class)\nexpect object Env\n", ios0 + "actual object Env\n",
            listOf("WARM expect-actual Env: expect object added")),
        Case("replace one pair by another (rename)", common0.replace("platformTag", "platformName"), ios0.replace("platformTag", "platformName"),
            listOf("WARM expect-actual platformName: expect fun added", "WARM expect-actual platformTag: expect fun removed")),
        // negative: the set of pairs is unchanged
        Case("actual body only", common0, ios0.replace("tag-1", "tag-2"), emptyList()),
        Case("common body only", common0.replace("d1", "d2"), ios0, emptyList()),
        Case("expect signature only (the classifier makes it warm)", common0.replace("platformTag()", "platformTag(n: Int = 0)"),
            ios0.replace("platformTag()", "platformTag(n: Int)"), emptyList()),
        Case("a plain function added", common0 + "fun helper(): Int = 1\n", ios0, emptyList()),
        Case("text that only looks like expect", common0 + "// expect fun notReal()\nval s = \"expect fun fake(): Int\"\n", ios0, emptyList()),
        Case("expect moved to another file", "package k\n\nfun dateLine(): String = \"d1\"\n", ios0, emptyList(),
            extraFile = "Moved.kt" to "package k\n\nexpect fun platformTag(): String\n"),
    )

    private fun module(name: String, common: String, ios: String, extra: Pair<String, String>?): List<File> {
        val c = File(tmp, "$name/commonMain").apply { mkdirs() }
        val i = File(tmp, "$name/iosMain").apply { mkdirs() }
        File(c, "Extras.kt").writeText(common)
        File(i, "Extras.ios.kt").writeText(ios)
        extra?.let { (f, text) -> File(c, f).writeText(text) }
        return listOf(c, i)
    }

    private fun declarations(dirs: List<File>) = dirs.flatMap { expectDeclarations(it) }.toSortedSet()

    @Test
    fun `adding or removing an expect-actual pair is warm, other edits are left to the classifier`() {
        val before = declarations(module("before", common0, ios0, null))
        val results = cases.mapIndexed { i, c ->
            val after = declarations(module("after$i", c.common, c.ios, c.extraFile))
            Triple(c, expectActualReasons(before, after), c.expect)
        }
        val failed = results.filter { (_, got, want) -> got != want }
        assertEquals(emptyList<String>(), failed.map { (c, got, want) -> "${c.name}: got $got, want $want" })
        assertEquals(6, cases.count { it.expect.isNotEmpty() })
        assertEquals(6, cases.count { it.expect.isEmpty() })
    }

    @Test
    fun `regression - an object with only a file initializer belongs to the one module of a single-module app`() {
        // 0.2.0 fix: the KMP template's resource accessors compile to objects whose only global symbol is
        // __Konan_init_<hash> (no package); with one module that object is the module's, never "cannot tell".
        val shared = HrModule(":shared", ModuleKind.APP, "/s", emptyList())
        val ui = HrModule(":ui", ModuleKind.LIBRARY, "/u", emptyList())
        val initOnly = setOf("__Konan_init_59afbfb0b603c12f")
        assertEquals(shared, Pipeline.moduleOf(listOf(shared), mapOf(shared to setOf("org.example")), { initOnly }))
        assertNull(Pipeline.moduleOf(listOf(shared, ui), mapOf(shared to setOf("org.example"), ui to setOf("myui")), { initOnly }))
    }

    @Test
    fun `regression - archives compiled by a failed attempt are still sent by the next one`() {
        // 0.2.0 fix: the compiler lists only the caches it built in the current run; an attempt that compiled and
        // then failed (e.g. a wrong edit, then a fix that leaves that file untouched) used to end in
        // "no module object was rebuilt". The pending list carries them until a patch is sent.
        val first = listOf("/w/per-file-cache/a/Greeting.kt.a", "/w/other/stdlib.a", "/w/per-file-cache/b/App.kt.o")
        val pending = Pipeline.pendingArchives(emptyList(), first)
        assertEquals(listOf("/w/per-file-cache/a/Greeting.kt.a"), pending)
        val second = Pipeline.pendingArchives(pending, listOf("/w/per-file-cache/c/Extras.kt.a", " /w/per-file-cache/a/Greeting.kt.a"))
        assertEquals(listOf("/w/per-file-cache/a/Greeting.kt.a", "/w/per-file-cache/c/Extras.kt.a"), second)
        assertEquals(second, Pipeline.pendingArchives(second, emptyList()))
    }
}
