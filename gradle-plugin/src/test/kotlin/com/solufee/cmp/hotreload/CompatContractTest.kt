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

    @Test
    fun `hr-state json is parsed per INTERFACE 10_3`() {
        assertEquals(WarmRestart.State(2, 3), WarmRestart.parseState("""{"page":2,"count":3}"""))
        assertEquals(WarmRestart.State(1, 0), WarmRestart.parseState("""{ "page" : 1 }"""))
        assertNull(WarmRestart.parseState("garbage"))
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
