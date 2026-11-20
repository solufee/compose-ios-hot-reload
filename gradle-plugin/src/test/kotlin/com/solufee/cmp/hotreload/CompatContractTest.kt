package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The plugin follows compat-classify.py by exit code only (INTERFACE §10.1): 0 hot, 3 warm restart,
 * anything else = tool error, nothing sent. These tests pin that contract against the copy in the plugin jar.
 */
class CompatContractTest {
    @TempDir
    lateinit var tmp: File

    private val tools by lazy { Tools.extract(File(tmp, "work"), null) }

    private fun classify(old: File, new: File): Proc.Result =
        Proc.run(listOf("python3", "-I", tools.compatClassify.path, "classify", "--old", old.path, "--new", new.path), echo = false)

    @Test
    fun `classifier is bundled with its recorded sha256`() {
        assertTrue("compat-classify.py" in Tools.NAMES)
        val sha = Tools.bundledManifest()["compat-classify.py"]
        assertEquals(sha, Tools.sha256(tools.compatClassify.readBytes()))
        assertTrue(tools.describe().contains("compat-classify.py="))
    }

    @Test
    fun `a file that is not a snapshot is a tool error, never hot`() {
        val bad = File(tmp, "bad.json").apply { writeText("{\"kind\": \"something-else\"}") }
        val r = classify(bad, bad)
        assertNotEquals(0, r.exit, r.output)
        assertNotEquals(3, r.exit, r.output)
    }

    private fun snap(name: String, topFunctions: String): File = File(tmp, "$name.json").apply {
        writeText("""{"kind": "cmp-hot-reload-compat", "version": 1, "module": "m", "klib": "m.klib", "classes": {},
            "topProperties": {}, "topFunctions": {$topFunctions}, "topOther": [], "bodies": {}, "klibSha256": "$name"}""")
    }

    private fun fn(name: String, params: String, composable: Boolean = false, key: String = "$name($params)") =
        """"$key": {"name": "$name", "params": "$params", "return": "kotlin/Unit", "visibility": "public", "modality": "final",
            "suspend": false, "inline": false, "composable": $composable}"""

    @Test
    fun `#28 - a changed composable signature is hot only with --force-recompose, and prints a remember-reset note`() {
        val old = snap("c0", fn("Badge", "text: kotlin/String", composable = true))
        val new = snap("c1", fn("Badge", "text: kotlin/String, rim: kotlin/String /* = ... */", composable = true))
        val off = classify(old, new)
        assertEquals(3, off.exit, off.output)
        assertTrue(off.output.contains("WARM signature Badge: ") && off.output.contains("forceRecompose off"), off.output)
        val on = Proc.run(listOf("python3", "-I", tools.compatClassify.path, "classify", "--old", old.path, "--new", new.path, "--force-recompose"), echo = false)
        assertEquals(0, on.exit, on.output)
        val note = on.output.lines().single { it.startsWith("NOTE ") }
        assertTrue(note.startsWith("NOTE remember-reset Badge: "), note)
        assertEquals("compat: note: Badge changed its signature; the remember state inside Badge resets once (its callers keep theirs)", hotNote(note))
    }

    @Test
    fun `#28 - a plain top-level signature change is hot, also against a baseline keyed by name (older script)`() {
        val old = snap("p0", fn("uiLabel", "", key = "uiLabel"))
        val new = snap("p1", fn("uiLabel", "suffix: kotlin/String /* = ... */"))
        val r = classify(old, new)
        assertEquals(0, r.exit, r.output)
        assertTrue(r.output.lines().none { it.startsWith("NOTE ") }, r.output)
    }

    @Test
    fun `hr-state json is parsed per INTERFACE 10_3`() {
        assertEquals(WarmRestart.State(2, 3), WarmRestart.parseState("""{"page":2,"count":3}"""))
        assertEquals(WarmRestart.State(1, 0), WarmRestart.parseState("""{ "page" : 1 }"""))
        assertNull(WarmRestart.parseState("garbage"))
        assertNull(WarmRestart.parseState("""{"draft":"hello","liked":true}"""))   // app-only state: no --hr-state tokens
        assertEquals(WarmRestart.State(2, 3), WarmRestart.parseState("""{"page":2,"count":3,"liked":true}"""))
    }

    @Test
    fun `klibs next to the dist are linked, only the dist's own klib dir is implicit`() {
        val dist = File(tmp, "tc/kotlin-native")
        val lines = listOf(
            "# comment", "",
            "${dist.path}/klib/common/stdlib",
            "${dist.path}/klib/platform/ios_simulator_arm64/org.jetbrains.kotlin.native.platform.UIKit",
            "${tmp.path}/tc/public-klibs/kotlinx-coroutines-core.klib",
            "${dist.path}/extra/skiko.klib",
            "/elsewhere/compose-ui.klib",
        )
        assertEquals(
            listOf("${tmp.path}/tc/public-klibs/kotlinx-coroutines-core.klib", "${dist.path}/extra/skiko.klib", "/elsewhere/compose-ui.klib"),
            Pipeline.externalKlibs(lines, dist),
        )
    }

    @Test
    fun `expect declarations are found in Kotlin sources`() {
        val d = File(tmp, "common").apply { mkdirs() }
        File(d, "Platform.kt").writeText("""
            package k
            expect fun getPlatform(): Platform
            internal expect val tag: String
            expect class Box(v: Int) { fun get(): Int }
            @OptIn(X::class) public expect object Clock
            expect abstract class Base
            expect fun <T> List<T>.second(): T
            fun notExpect(): String = "expect fun fake()"
            actual fun alreadyActual() = 1
        """.trimIndent())
        assertEquals(setOf("fun getPlatform", "val tag", "class Box", "object Clock", "class Base", "fun second"), expectDeclarations(d))
    }

    @Test
    fun `booted simulators are listed`() {
        val out = "== Devices ==\n-- iOS 27.2 --\n    HR (67FC79E4-473A-47C9-87C0-1ADEA85CBBBB) (Booted) \n    Other (0B879832-98D9-49A5-AC00-8BAB579AFBB2) (Shutdown) \n"
        assertEquals(listOf("HR (67FC79E4-473A-47C9-87C0-1ADEA85CBBBB) (Booted)"), Launcher.bootedDevices(out))
    }

    @Test
    fun `app pid is found with and without launch arguments`() {
        val ps = """
              11 /usr/bin/other
            4242 /Volumes/sim/Library/Developer/CoreSimulator/Devices/U1/data/Containers/Bundle/Application/A/ComposeHR.app/ComposeHR --hr-state=2,3
            4343 /Volumes/sim/Library/Developer/CoreSimulator/Devices/U2/data/Containers/Bundle/Application/B/ComposeHR.app/ComposeHR
        """.trimIndent()
        assertEquals(4242L, Launcher.findAppPid(ps, "U1", "ComposeHR"))
        assertEquals(4343L, Launcher.findAppPid(ps, "U2", "ComposeHR"))
        assertNull(Launcher.findAppPid(ps, "U3", "ComposeHR"))
        assertNull(Launcher.findAppPid(ps, "U1", "ComposeH"))
    }

    /** Compiles real klibs with the pinned toolchain; skipped without -Pcmp.hotreload.toolchainDir. */
    @Test
    fun `real snapshots - body change is hot (0), new field is warm (3)`() {
        val dir = System.getProperty("cmp.hotreload.toolchainDir")
        assumeTrue(dir != null && File(dir, TOOLCHAIN_MANIFEST).isFile, "no toolchain configured; classifier contract test skipped")
        val dist = File(dir!!)
        val kotlinc = File(dist, "bin/kotlinc-native").path
        val klibTool = File(dist, "bin/klib").path
        val javaHome = System.getProperty("java.home")

        fun klib(name: String, source: String): File {
            val src = File(tmp, "$name/src/lib.kt").apply { parentFile.mkdirs(); writeText(source) }
            val out = File(tmp, "$name/$name")
            Proc.check(listOf(kotlinc, "-target", "ios_simulator_arm64", "-p", "library", "-g", src.path, "-o", out.path),
                env = mapOf("JAVA_HOME" to javaHome), echo = false)
            return File(out.path + ".klib")
        }
        fun snapshot(k: File): File {
            val out = File(k.parentFile, "snap.json")
            Proc.check(listOf("python3", "-I", tools.compatClassify.path, "snapshot", "--klib", k.path, "--out", out.path, "--klib-tool", klibTool),
                env = mapOf("JAVA_HOME" to javaHome), echo = false)
            return out
        }
        val base = snapshot(klib("base", "package p\nclass Foo(val a: Int) { fun f(): Int = a + 1 }\n"))
        val body = snapshot(klib("body", "package p\nclass Foo(val a: Int) { fun f(): Int = a + 2 }\n"))
        val field = snapshot(klib("field", "package p\nclass Foo(val a: Int) { val b: Int = 7\n fun f(): Int = a + 1 }\n"))

        val hot = classify(base, body)
        assertEquals(0, hot.exit, hot.output)
        val warm = classify(base, field)
        assertEquals(3, warm.exit, warm.output)
        assertTrue(warm.output.lines().any { it.startsWith("WARM field-layout p/Foo") }, warm.output)
    }
}
