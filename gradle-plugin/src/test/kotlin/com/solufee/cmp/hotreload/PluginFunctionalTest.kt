package com.solufee.cmp.hotreload

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PluginFunctionalTest {
    @TempDir
    lateinit var project: File

    private fun setup(toolchain: File?, modules: String = """app(":app")
            library(":ui")""") {
        File(project, "settings.gradle.kts").writeText("""rootProject.name = "t"
include("app", "ui")
""")
        File(project, "app").mkdirs()
        File(project, "ui").mkdirs()
        File(project, "build.gradle.kts").writeText(
            """
            plugins { id("com.solufee.cmp.hotreload") }
            cmpHotReload {
                ${toolchain?.let { "toolchainDir = file(\"${it.path}\")" } ?: ""}
                $modules
            }
            tasks.register("plainBuild") { doLast { println("PLAIN OK") } }
            """.trimIndent(),
        )
    }

    private fun runner(vararg args: String) = GradleRunner.create()
        .withProjectDir(project)
        .withPluginClasspath()
        .withArguments(*args, "-Pcmp.hotreload.probeHost=false", "--configuration-cache", "--stacktrace")

    @Test
    fun `mismatched interfaceVersion fails with a fix hint`() {
        val tc = File(project, "tc").apply { mkdirs() }
        File(tc, TOOLCHAIN_MANIFEST).writeText("""{ "interfaceVersion": 99, "kotlin": {}, "compose": {}, "llvm": {}, "host": {}, "runtime": {} }""")
        setup(tc)
        val r = runner("hotReloadCheckToolchain").buildAndFail()
        assertTrue("interfaceVersion: expected ${PinnedToolchain.current.interfaceVersion}, found 99" in r.output, r.output)
        assertTrue("update the plugin to one built for interfaceVersion 99" in r.output, r.output)
    }

    @Test
    fun `an old downloaded toolchain is told which release to fetch`() {
        val tc = File(project, "tc").apply { mkdirs() }
        File(tc, TOOLCHAIN_MANIFEST).writeText("""{ "interfaceVersion": 6, "layout": "local", "toolchainVersion": "2.5.255-hotreload.2", "kotlin": {}, "compose": {}, "llvm": {}, "host": {}, "runtime": {} }""")
        setup(tc)
        val r = runner("hotReloadCheckToolchain").buildAndFail()
        assertTrue("(downloaded toolchain 2.5.255-hotreload.2)" in r.output, r.output)
        assertTrue("This plugin works with toolchain ${PinnedToolchain.current.toolchainVersion}" in r.output, r.output)
    }

    @Test
    fun `no toolchain configured explains every way to set one`() {
        setup(null)
        val r = runner("hotReloadCheckToolchain").buildAndFail()
        assertTrue("no toolchain configured" in r.output, r.output)
        assertTrue("cmp.hotreload.toolchainDir" in r.output, r.output)
    }

    @Test
    fun `wrong module set is reported by the check, the rest of the build still works`() {
        setup(null, modules = """library(":ui")""")
        val plain = runner("plainBuild").build()
        assertEquals(TaskOutcome.SUCCESS, plain.task(":plainBuild")?.outcome)
        val r = runner("hotReloadCheckToolchain").buildAndFail()
        assertTrue("exactly one app(...) module is required" in r.output, r.output)
    }

    @Test
    fun `disabled switch skips hot reload tasks`() {
        setup(null)
        val r = runner("hotReloadPackage", "-Pcmp.hotreload.enabled=false").build()
        assertEquals(TaskOutcome.SKIPPED, r.task(":hotReloadPackage")?.outcome)
        assertEquals(TaskOutcome.SKIPPED, r.task(":hotReloadCheckToolchain")?.outcome)
    }

    @Test
    fun `applying the plugin leaves every other task unchanged`() {
        // Same project with and without the plugin: outside the "Hot reload" group, `tasks --all` must match.
        fun listing(withPlugin: Boolean): String {
            File(project, "settings.gradle.kts").writeText("rootProject.name = \"t\"\ninclude(\"app\", \"ui\")\n")
            File(project, "app").mkdirs()
            File(project, "ui").mkdirs()
            File(project, "build.gradle.kts").writeText(
                (if (withPlugin) "plugins { id(\"com.solufee.cmp.hotreload\") }\ncmpHotReload { app(\":app\"); library(\":ui\") }\n" else "") +
                    "plugins.apply(\"base\")\ntasks.register(\"releaseLike\") { group = \"build\"; doLast { } }\n",
            )
            val out = runner("tasks", "--all").build().output
            return out.substringAfter("Tasks runnable from root project").lines()
                .filterNot { it.startsWith("hotReload") || it.startsWith("Hot reload tasks") || it.matches(Regex("-+")) }
                .filterNot { it.startsWith("BUILD") || it.contains("actionable task") || it.contains("onfiguration cache") }
                .joinToString("\n").replace(Regex("\n{2,}"), "\n")
        }
        assertEquals(listing(false), listing(true))
    }

    @Test
    fun `warmRestart is a DSL setting and hotReload offers --no-warm`() {
        setup(null, modules = """app(":app")
            library(":ui")
            warmRestart = false""")
        val r = runner("help", "--task", "hotReload").build()
        assertTrue("--no-warm" in r.output, r.output)
        assertTrue("warm restart" in r.output, r.output)
    }

    @Test
    fun `plugin adds tasks only in its own group`() {
        setup(null)
        val r = runner("tasks", "--all").build()
        val hot = r.output.substringAfter("Hot reload tasks").substringBefore("\n\n")
        listOf("hotReload ", "hotReloadPackage", "hotReloadRun", "hotReloadCheckToolchain", "hotReloadDaemonStart", "hotReloadDaemonStop", "hotReloadCompileKlibUi", "hotReloadCompileKlibs")
            .forEach { assertTrue(it in hot, "missing $it in:\n$hot") }
    }
}
