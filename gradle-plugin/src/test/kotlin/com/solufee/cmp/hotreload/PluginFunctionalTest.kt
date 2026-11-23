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
    fun `applied to a module with declared source sets, the module gets the tasks`() {
        File(project, "settings.gradle.kts").writeText("rootProject.name = \"t\"\ninclude(\"shared\")\n")
        File(project, "build.gradle.kts").writeText("")
        File(project, "shared/src/commonMain/kotlin").mkdirs()
        File(project, "shared/src/iosMain/kotlin").mkdirs()
        File(project, "shared/build.gradle.kts").writeText(
            """
            plugins { id("com.solufee.cmp.hotreload") }
            cmpHotReload {
                app(":shared") {
                    iosEntry = "org.example.MainViewController"
                    sourceSets {
                        create("commonMain") { srcDir("src/commonMain/kotlin") }
                        create("iosMain") { srcDir("src/iosMain/kotlin"); dependsOn("commonMain") }
                    }
                }
            }
            """.trimIndent(),
        )
        val r = runner(":shared:tasks", "--all").build()
        val hot = r.output.substringAfter("Hot reload tasks").substringBefore("\n\n")
        listOf("hotReload ", "hotReloadPackage", "hotReloadRun", "hotReloadClean").forEach { assertTrue(it in hot, "missing $it in:\n$hot") }
        val check = runner(":shared:hotReloadCheckToolchain").buildAndFail()
        assertTrue("no toolchain configured" in check.output, check.output)
        assertTrue("exactly one app" !in check.output && "cannot discover" !in check.output, check.output)
    }

    @Test
    fun `plugin adds tasks only in its own group`() {
        setup(null)
        val r = runner("tasks", "--all").build()
        val hot = r.output.substringAfter("Hot reload tasks").substringBefore("\n\n")
        listOf("hotReload ", "hotReloadPackage", "hotReloadRun", "hotReloadCheckToolchain", "hotReloadDaemonStart", "hotReloadDaemonStop", "hotReloadClean", "hotReloadCompileKlibUi", "hotReloadCompileKlibs")
            .forEach { assertTrue(it in hot, "missing $it in:\n$hot") }
    }

    @Test
    fun `hotReload declares the hot modules' sources and resources as inputs, hotReloadWatch reuses the configuration cache`() {
        setup(null)
        File(project, "app/src/main/kotlin").mkdirs()
        File(project, "app/src/main/kotlin/main.kt").writeText("fun main() {}\n")
        File(project, "ui/src/main/kotlin").mkdirs()
        File(project, "ui/src/main/kotlin/ui.kt").writeText("package myui\n")
        File(project, "ui/src/commonMain/composeResources/values").mkdirs()
        File(project, "ui/src/commonMain/composeResources/values/strings.xml").writeText("<resources/>\n")
        File(project, "ui/build/generated/x").mkdirs()
        File(project, "build.gradle.kts").appendText(
            "\ngradle.projectsEvaluated { tasks.register(\"showInputs\") { val files = tasks.named(\"hotReload\").get().inputs.files.files" +
                ".map { it.relativeTo(rootDir).path }.sorted(); doLast { println(\"INPUTS \" + files) } } }\n",
        )
        val r = GradleRunner.create().withProjectDir(project).withPluginClasspath()
            .withArguments("showInputs", "-Pcmp.hotreload.probeHost=false").build()
        val line = r.output.lines().first { it.startsWith("INPUTS ") }
        assertTrue("app/src/main/kotlin," in line && "ui/src/main/kotlin" in line, line)
        assertTrue("ui/src/commonMain/composeResources" in line, line)
        assertTrue("build/generated" !in line, line)
        val first = runner("hotReloadWatch", "--dry-run").build()
        assertTrue(":hotReloadWatch SKIPPED" in first.output, first.output)
        val second = runner("hotReloadWatch", "--dry-run").build()
        assertTrue("Reusing configuration cache" in second.output, second.output)
    }

    @Test
    fun `hotReloadRun runs after hotReloadPackage whatever the order on the command line (#76)`() {
        setup(null)
        File(project, "app/src/main/kotlin").mkdirs()
        File(project, "app/src/main/kotlin/main.kt").writeText("fun main() {}\n")
        File(project, "ui/src/main/kotlin").mkdirs()
        File(project, "ui/src/main/kotlin/ui.kt").writeText("package myui\n")
        val r = runner("hotReloadRun", "hotReloadPackage", "hotReload", "--dry-run").build()
        val order = r.output.lines().filter { it.startsWith(":hotReload") }.map { it.substringBefore(' ') }
        val pkg = order.indexOf(":hotReloadPackage")
        val run = order.indexOf(":hotReloadRun")
        val reload = order.indexOf(":hotReload")
        assertTrue(pkg in 0 until run && run < reload, order.toString())
    }

    @Test
    fun `hotReloadRun alone builds the klibs first, so it can package after hotReloadClean (#93)`() {
        setup(null)
        File(project, "app/src/main/kotlin").mkdirs()
        File(project, "app/src/main/kotlin/main.kt").writeText("fun main() {}\n")
        File(project, "ui/src/main/kotlin").mkdirs()
        File(project, "ui/src/main/kotlin/ui.kt").writeText("package myui\n")
        val r = runner("hotReloadRun", "--dry-run").build()
        val order = r.output.lines().filter { it.startsWith(":hotReload") }.map { it.substringBefore(' ') }
        val klib = order.indexOfFirst { it.startsWith(":hotReloadCompileKlib") }
        assertTrue(klib in 0 until order.indexOf(":hotReloadRun"), order.toString())
        assertTrue(":hotReloadPackage" !in order, "an existing package is launched as it is: $order")
        // in one command, whatever the order on the command line, the clean comes first
        val both = runner("hotReloadRun", "hotReloadClean", "--dry-run").build().output.lines()
            .filter { it.startsWith(":hotReload") }.map { it.substringBefore(' ') }
        assertTrue(both.indexOf(":hotReloadClean") in 0 until both.indexOfFirst { it.startsWith(":hotReloadCompileKlib") }, both.toString())
    }

    @Test
    fun `the build identity is checked before anything compiles or launches (#98)`() {
        setup(null)
        File(project, "app/src/main/kotlin").mkdirs()
        File(project, "app/src/main/kotlin/main.kt").writeText("fun main() {}\n")
        File(project, "ui/src/main/kotlin").mkdirs()
        File(project, "ui/src/main/kotlin/ui.kt").writeText("package myui\n")
        val pkg = runner("hotReloadPackage", "--dry-run").build().output.lines().filter { it.startsWith(":hotReload") }.map { it.substringBefore(' ') }
        val id = pkg.indexOf(":hotReloadBuildIdentity")
        assertTrue(id in 0 until pkg.indexOfFirst { it.startsWith(":hotReloadCompileKlib") }, pkg.toString())
        val run = runner("hotReloadRun", "--dry-run").build().output.lines().filter { it.startsWith(":hotReload") }.map { it.substringBefore(' ') }
        assertTrue(run.indexOf(":hotReloadBuildIdentity") in 0 until run.indexOf(":hotReloadRun"), run.toString())
    }

    @Test
    fun `extraCompilerArgs, global and per module, in both option spellings, keep the configuration cache (#133)`() {
        setup(null, modules = """app(":app")
            library(":ui")
            extraCompilerArgs.addAll("-opt-in", "a.B", "-Xcontext-parameters")
            extraCompilerArgs(":ui", "-language-version=2.1", "-opt-in", "c.D")""")
        File(project, "app/src/main/kotlin").mkdirs()
        File(project, "app/src/main/kotlin/main.kt").writeText("fun main() {}\n")
        File(project, "ui/src/main/kotlin").mkdirs()
        File(project, "ui/src/main/kotlin/ui.kt").writeText("package myui\n")
        val first = runner("hotReloadPackage", "--dry-run").build()
        assertTrue(":hotReloadBuildIdentity" in first.output, first.output)
        val again = runner("hotReloadPackage", "--dry-run").build()
        assertTrue("Reusing configuration cache" in again.output, again.output)
    }

    @Test
    fun `the hot reload tasks store in the configuration cache (no Project captured by the settings, #98)`() {
        // a toolchain manifest, so the settings provider has a value to compute (the check task then fails on it)
        val tc = File(project, "tc").apply { mkdirs() }
        File(tc, TOOLCHAIN_MANIFEST).writeText("""{ "interfaceVersion": 99, "kotlin": {}, "compose": {}, "llvm": {}, "host": {}, "runtime": {} }""")
        setup(tc)
        File(project, "app/src/main/kotlin").mkdirs()
        File(project, "app/src/main/kotlin/main.kt").writeText("fun main() {}\n")
        File(project, "ui/src/main/kotlin").mkdirs()
        File(project, "ui/src/main/kotlin/ui.kt").writeText("package myui\n")
        val out = runner("hotReloadBuildIdentity", "hotReloadRun").buildAndFail().output
        assertTrue("cannot serialize" !in out && "Configuration cache problems" !in out, out)
    }

    @Test
    fun `editing a source file keeps the configuration cache (the entry detection is a ValueSource)`() {
        setup(null)
        File(project, "app/src/main/kotlin").mkdirs()
        val main = File(project, "app/src/main/kotlin/main.kt").apply { writeText("fun main() { println(1) }\n") }
        File(project, "ui/src/main/kotlin").mkdirs()
        File(project, "ui/src/main/kotlin/ui.kt").writeText("package myui\nfun a() = 1\n")
        runner("plainBuild").build()
        main.writeText("fun main() { println(2) }\n")
        File(project, "ui/src/main/kotlin/ui.kt").writeText("package myui\nfun a() = 2\n")
        val again = runner("plainBuild").build()
        assertTrue("Reusing configuration cache" in again.output, again.output)
    }

    @Test
    fun `deviceFamily - invalid values are reported, an app module with its own Info plist keeps it and is warned`() {
        setup(null, modules = """app(":app")
            library(":ui")
            deviceFamily = listOf(1, 3)""")
        val bad = runner("hotReloadCheckToolchain").buildAndFail()
        assertTrue("deviceFamily = [1, 3]: only 1 (iPhone) and 2 (iPad) are allowed" in bad.output, bad.output)
        File(project, "app/Info.plist").writeText("<plist/>\n")
        setup(null, modules = """app(":app")
            library(":ui")
            deviceFamily = listOf(1, 2)""")
        File(project, "app/Info.plist").writeText("<plist/>\n")
        val own = runner("hotReloadCheckToolchain").buildAndFail()
        assertTrue("deviceFamily is ignored: the app module has its own" in own.output, own.output)
        assertTrue("only 1 (iPhone) and 2 (iPad)" !in own.output, own.output)
    }
}
