package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * #137: hot reload compiles get the module's own compiler settings (#133), so the Kotlin the plugin generates
 * (HotReloadMain.kt) must compile without a single diagnostic in the strictest modes a project can set.
 */
class GeneratedSourceTest {
    @TempDir
    lateinit var tmp: File

    private val source = Pipeline.generatedMainSource("com.example.MainViewController", "App")

    @Test
    fun `every declaration has a visibility and explicit types, only main is public`() {
        val decls = source.lines().filter { l -> listOf("class ", "object ", "constructor(", "fun ").any { it in l } && !l.trimStart().startsWith("//") }
        assertTrue(decls.isNotEmpty())
        decls.filterNot { it.trimStart().startsWith("override ") }.forEach { l ->
            assertTrue(listOf("public ", "internal ", "private ").any { it in l }, "no visibility: $l")
        }
        assertTrue("internal class HotReloadAppDelegate" in source && "public fun main(args: Array<String>)" in source, source)
        assertTrue("override fun window(): UIWindow? =" in source, source)
        assertFalse(Regex("""fun \w+\([^)]*\) =""").containsMatchIn(source), "an expression body without a return type")
    }

    /** The generated source, with an entry function, through the toolchain's compiler (skipped without -Pcmp.hotreload.toolchainDir). */
    @Test
    fun `the generated source compiles without a diagnostic under explicit API, -Werror and progressive mode`() {
        val dir = System.getProperty("cmp.hotreload.toolchainDir")
        assumeTrue(dir != null && File(dir, TOOLCHAIN_MANIFEST).isFile, "no toolchain configured; compile test skipped")
        val main = File(tmp, "HotReloadMain.kt").apply { writeText(source) }
        val entry = File(tmp, "Entry.kt").apply {
            writeText("package com.example\n\nimport platform.UIKit.UIViewController\n\npublic fun MainViewController(): UIViewController = UIViewController()\n")
        }
        val modes = listOf(
            listOf("-Xexplicit-api=strict"),
            listOf("-Xexplicit-api=warning", "-Werror"),
            listOf("-Werror", "-progressive"),
            listOf("-Xexplicit-api=strict", "-Werror", "-progressive"),
        )
        for (mode in modes) {
            val out = File(tmp, "out-${mode.joinToString("").replace(Regex("[^A-Za-z]"), "")}")
            val r = Proc.run(listOf(File(dir!!, "bin/kotlinc-native").path, "-target", "ios_simulator_arm64", "-p", "library") + mode +
                listOf("-opt-in=kotlinx.cinterop.ExperimentalForeignApi", main.path, entry.path, "-o", out.path), echo = false)
            val diagnostics = r.output.lines().filter { "HotReloadMain.kt" in it && ("error:" in it || "warning:" in it) }
            assertEquals(emptyList<String>(), diagnostics, "mode $mode")
            assertEquals(0, r.exit, "mode $mode: ${r.output}")
        }
    }
}
