package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 0.2.0 rule, narrowed in 0.2.4 (#28): adding or removing an expect/actual pair of a class, object, interface,
 * property or typealias is a warm restart; an `expect fun` pair is a plain function after actualization, so adding,
 * removing or renaming one is hot. Edits that keep the set of pairs are left to the classifier (bodies hot,
 * function signatures hot through compat-classify.py since #28).
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
        // positive: a pair that is not a function added or removed
        Case("add an expect class pair", common0 + "expect class Clock() { fun now(): Long }\n", ios0 + "actual class Clock actual constructor() { actual fun now(): Long = 0 }\n",
            listOf("WARM expect-actual Clock: expect class added")),
        Case("add an expect val pair", common0 + "internal expect val tag: String\n", ios0 + "internal actual val tag: String = \"t\"\n",
            listOf("WARM expect-actual tag: expect val added")),
        Case("add an annotated expect object pair", common0 + "@OptIn(ExperimentalStdlibApi::class)\nexpect object Env\n", ios0 + "actual object Env\n",
            listOf("WARM expect-actual Env: expect object added")),
        Case("add an expect interface pair", common0 + "expect interface Source { fun read(): Int }\n", ios0 + "actual interface Source { actual fun read(): Int }\n",
            listOf("WARM expect-actual Source: expect interface added")),
        // negative: the set of pairs is unchanged, or only expect fun pairs changed (#28)
        Case("add an expect/actual fun pair (#28: hot)", common0 + "expect fun flavor(): String\n", ios0 + "actual fun flavor(): String = \"sim\"\n", emptyList()),
        Case("remove the only fun pair (#28: hot)", "package k\n\nfun dateLine(): String = \"d1\"\n", "package k\n", emptyList()),
        Case("replace one fun pair by another (rename, #28: hot)", common0.replace("platformTag", "platformName"), ios0.replace("platformTag", "platformName"), emptyList()),
        Case("actual body only", common0, ios0.replace("tag-1", "tag-2"), emptyList()),
        Case("common body only", common0.replace("d1", "d2"), ios0, emptyList()),
        Case("expect signature only (judged by the classifier: hot since #28)", common0.replace("platformTag()", "platformTag(n: Int = 0)"),
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
    fun `adding or removing an expect-actual pair other than a function is warm, other edits are left to the classifier`() {
        // A removed expect class pair (the set before has one).
        val withBox = declarations(module("box", common0 + "expect class Box(v: Int)\n", ios0 + "actual class Box actual constructor(v: Int)\n", null))
        assertEquals(listOf("WARM expect-actual Box: expect class removed"), expectActualReasons(withBox, declarations(module("nobox", common0, ios0, null))))

        val before = declarations(module("before", common0, ios0, null))
        val results = cases.mapIndexed { i, c ->
            val after = declarations(module("after$i", c.common, c.ios, c.extraFile))
            Triple(c, expectActualReasons(before, after), c.expect)
        }
        val failed = results.filter { (_, got, want) -> got != want }
        assertEquals(emptyList<String>(), failed.map { (c, got, want) -> "${c.name}: got $got, want $want" })
        assertEquals(4, cases.count { it.expect.isNotEmpty() })
        assertEquals(9, cases.count { it.expect.isEmpty() })
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
    fun `regression - the generated UIKit entry (root package) belongs to the app when there are library modules`() {
        // 0.2.1 fix: shared -> core -> base; the plugin's HotReloadMain.kt (root package) used to match no module.
        val shared = HrModule(":shared", ModuleKind.APP, File(tmp, "s").apply { mkdirs() }.path, emptyList())
        val core = HrModule(":core", ModuleKind.LIBRARY, File(tmp, "c").apply { mkdirs(); File(this, "C.kt").writeText("package org.example.core\n") }.path, emptyList())
        val s = HrSettings(
            rootDir = tmp.path, workDir = File(tmp, "w").path, dist = "/d", kotlinc = "/k", compilerJar = "/c.jar", composePlugin = "/p.jar",
            klibList = "/k.txt", kaldoDylib = "/l.dylib", kaldoSha256 = null, target = "ios_simulator_arm64", orcRuntime = "/o.a",
            objcUtilsLib = "/u.a", llvmHome = null, useDaemon = false, socketDir = "/s", javaHome = "/j", modules = listOf(shared, core),
            infoPlist = "/p", linkerOptions = emptyList(), keepCompactUnwind = true, port = 5567, udid = "booted", launchLog = "/l",
            readyMarker = null, launchTimeoutSec = 1, launchRetries = 1, reinstall = true, warmRestart = true,
            iosEntry = "org.example.kmphr.MainViewController",
        )
        val p = Pipeline(s, org.gradle.api.logging.Logging.getLogger("t"))
        val packages = s.modules.associateWith { p.packagesOf(it) }
        val entryObject = setOf("_kfun:#main(kotlin.Array<kotlin.String>){}", "_kclass:HotReloadAppDelegate")
        assertEquals(shared, Pipeline.moduleOf(s.modules, packages) { entryObject })
        assertEquals(core, Pipeline.moduleOf(s.modules, packages) { setOf("_kfun:org.example.core#coreLine(){}kotlin.String") })
    }

    @Test
    fun `a changed Compose resource makes the reload warm, an unchanged set does not`() {
        val res = File(tmp, "assembled/composeResources/kmphr.core.generated.resources/values").apply { mkdirs() }
        val strings = File(res, "strings.commonMain.cvr").apply { writeText("core_title=v1") }
        val root = File(tmp, "assembled")
        val before = Pipeline.resourceDigests(listOf(root))
        assertEquals(emptyList<String>(), resourceReasons(listOf()))
        strings.writeText("core_title=v2")
        File(res, "extra.cvr").writeText("x")
        val now = Pipeline.resourceDigests(listOf(root))
        val changed = (before.keys + now.keys).sorted().filter { before[it] != now[it] }
        assertEquals(listOf("composeResources/kmphr.core.generated.resources/values/extra.cvr",
            "composeResources/kmphr.core.generated.resources/values/strings.commonMain.cvr"), changed)
        val r = resourceReasons(changed).single()
        assertTrue(r.startsWith("WARM compose-resources: 2 file(s) changed since packaging"), r)
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
