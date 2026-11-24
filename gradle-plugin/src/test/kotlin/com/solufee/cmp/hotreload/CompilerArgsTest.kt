package com.solufee.cmp.hotreload

import org.gradle.api.DefaultTask
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** #133: the project's compiler settings in hot reload compiles. */
class CompilerArgsTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `an option with a value is one pair in either spelling`() {
        assertEquals(listOf(CompilerArgs.Opt("-opt-in", "a.B"), CompilerArgs.Opt("-opt-in", "c.D")),
            CompilerArgs.parse(listOf("-opt-in", "a.B", "-opt-in=c.D")))
        assertEquals(listOf(CompilerArgs.Opt("-language-version", "2.1"), CompilerArgs.Opt("-Xcontext-parameters", null)),
            CompilerArgs.parse(listOf("-language-version=2.1", "-Xcontext-parameters")))
        // a value is never taken for an option without one, and -X flags with = keep their value
        assertEquals(listOf(CompilerArgs.Opt("-progressive", null), CompilerArgs.Opt("-Xsuppress-warning", "NOTHING_TO_INLINE")),
            CompilerArgs.parse(listOf("-progressive", "-Xsuppress-warning=NOTHING_TO_INLINE")))
        assertEquals(listOf("-Xsuppress-warning=NOTHING_TO_INLINE"), CompilerArgs.Opt("-Xsuppress-warning", "NOTHING_TO_INLINE").render())
        assertEquals(listOf("-language-version", "2.1"), CompilerArgs.Opt("-language-version", "2.1").render())
        assertEquals(listOf("-opt-in=a.B"), CompilerArgs.Opt("-opt-in", "a.B").render())
    }

    @Test
    fun `opt-ins merge by value across spellings and comma lists`() {
        val m = CompilerArgs.merge(listOf("-opt-in", "a.B,c.D"), listOf("-opt-in=c.D"), listOf("-opt-in", "e.F", "-opt-in=a.B"))
        assertEquals(listOf("-opt-in=a.B", "-opt-in=c.D", "-opt-in=e.F"), m.args)
    }

    @Test
    fun `one language and API version, the module's extraCompilerArgs over the global ones over the build`() {
        val m = CompilerArgs.merge(listOf("-language-version", "2.0", "-api-version", "2.0"), listOf("-language-version=2.1"), listOf("-language-version", "2.2"))
        assertEquals(listOf("-api-version", "2.0", "-language-version", "2.2"), m.args)
        assertEquals(1, m.args.count { it == "-language-version" })
    }

    @Test
    fun `plugin-owned options are dropped, binary options with a warning, the rest kept once`() {
        val m = CompilerArgs.merge(
            listOf("-Xcontext-parameters", "-target", "ios_arm64", "-Xplugin=/x.jar", "-P", "plugin:a:b=c", "-o", "out", "-p", "program", "-g",
                "-Xmulti-platform", "-Xfragments=a,b", "-Xic-cache-dir=/ic", "-Xauto-cache-from=/a", "-Xdump-built-caches-to=/d",
                "-linker-option", "-lz", "-l", "/k.klib", "-module-name", "m", "-Xbinary=gc=cms", "-Xcontext-parameters", "-Werror"),
            emptyList(), emptyList())
        assertEquals(listOf("-Xcontext-parameters", "-Werror"), m.args)
        assertTrue(m.dropped.containsAll(listOf("-target ios_arm64", "-Xplugin=/x.jar", "-P plugin:a:b=c", "-o out", "-Xbinary=gc=cms", "-l /k.klib")), m.dropped.toString())
        assertEquals(1, m.warnings.size)
        assertTrue("-Xbinary=gc=cms" in m.warnings.single(), m.warnings.toString())
    }

    @Test
    fun `what a compile already has is not repeated (the plugin's opt-in, expect-actual classes)`() {
        val base = listOf("-target", "ios_simulator_arm64", "-p", "library", "-g", "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
            "-Xmulti-platform", "-Xexpect-actual-classes")
        val user = listOf("-opt-in=kotlinx.cinterop.ExperimentalForeignApi", "-opt-in=kotlin.ExperimentalStdlibApi", "-Xexpect-actual-classes", "-Xcontext-parameters")
        assertEquals(listOf("-opt-in=kotlin.ExperimentalStdlibApi", "-Xcontext-parameters"), CompilerArgs.without(user, base))
        // the paired spelling of the plugin's opt-in counts as the same option
        assertEquals(emptyList<String>(), CompilerArgs.without(listOf("-opt-in=kotlinx.cinterop.ExperimentalForeignApi"),
            listOf("-opt-in", "kotlinx.cinterop.ExperimentalForeignApi")))
    }

    @Test
    fun `the project's settings become the arguments KGP passes`() {
        assertEquals(listOf("-language-version", "2.1", "-api-version", "2.0", "-progressive", "-Werror", "-nowarn",
            "-opt-in", "a.B", "-XXLanguage:+ContextParameters", "-Xfoo"),
            CompilerArgs.fromProject(listOf("-Xfoo"), listOf("a.B"), "2.1", "2.0", true, true, true, listOf("ContextParameters")))
        assertEquals(emptyList<String>(), CompilerArgs.fromProject(emptyList(), emptyList(), null, null, false, false, false, emptyList()))
    }

    private fun compilerJar(vararg versions: String): File = File(tmp, "compiler.jar").apply {
        ZipOutputStream(outputStream()).use { z ->
            z.putNextEntry(ZipEntry("org/jetbrains/kotlin/config/LanguageVersion.class"))
            z.write(versions.joinToString("\u0001") { "KOTLIN_" + it.replace('.', '_') }.toByteArray())
            z.closeEntry()
        }
    }

    @Test
    fun `a language or API version the toolchain's compiler does not know is an error naming the known ones`() {
        val known = CompilerArgs.knownLanguageVersions(compilerJar("1.9", "2.0", "2.1", "2.5"))
        assertEquals(setOf("1.9", "2.0", "2.1", "2.5"), known)
        assertEquals(emptyList<String>(), CompilerArgs.unsupportedVersions(listOf("-language-version", "2.1"), known, "2.5.255"))
        val bad = CompilerArgs.unsupportedVersions(listOf("-language-version", "2.7", "-api-version", "2.1"), known, "2.5.255")
        assertEquals(1, bad.size)
        assertTrue("-language-version 2.7" in bad.single() && "1.9, 2.0, 2.1, 2.5" in bad.single() && "extraCompilerArgs" in bad.single(), bad.single())
        assertNull(CompilerArgs.knownLanguageVersions(File(tmp, "missing.jar")))
        assertEquals(emptyList<String>(), CompilerArgs.unsupportedVersions(listOf("-language-version", "9.9"), null, "2.5.255"), "unknown: the compiler says it")
    }

    @Test
    fun `hot reload compiles carry the module's arguments once, after the plugin's, and check the versions`() {
        val dir = File(tmp, "core/src/commonMain/kotlin").apply { mkdirs() }
        File(dir, "A.kt").writeText("package core\nfun a() = 1\n")
        val core = HrModule(":core", ModuleKind.LIBRARY, dir.path, emptyList(), compilerArgs = listOf("-Xcontext-parameters", "-opt-in=kotlinx.cinterop.ExperimentalForeignApi", "-Werror", "-Xexplicit-api=strict"))
        val app = HrModule(":app", ModuleKind.APP, File(tmp, "app").apply { mkdirs() }.path, listOf(":core"), compilerArgs = listOf("-language-version", "2.1", "-Werror", "-progressive", "-Xexplicit-api=strict"))
        val s = HrSettings(
            rootDir = tmp.path, workDir = File(tmp, "work").path, dist = "/d", kotlinc = "/k", compilerJar = compilerJar("2.0", "2.1").path,
            composePlugin = "/nonexistent.jar", klibList = File(tmp, "klibs.txt").apply { writeText("") }.path, kaldoDylib = "/l", kaldoSha256 = null,
            target = "ios_simulator_arm64", orcRuntime = "/o.a", objcUtilsLib = "/u.a", llvmHome = null, useDaemon = true, socketDir = "/s",
            javaHome = "/j", modules = listOf(app, core), infoPlist = "/p", linkerOptions = emptyList(), keepCompactUnwind = true, port = 1,
            udid = "booted", launchLog = "/l", readyMarker = null, launchTimeoutSec = 1, launchRetries = 1, reinstall = true, warmRestart = true,
        )
        val p = Pipeline(s, Logging.getLogger("t"))
        val lib = p.libraryArgs(core)
        assertEquals(1, lib.count { it == "-opt-in=kotlinx.cinterop.ExperimentalForeignApi" })
        assertTrue(lib.indexOf("-Xcontext-parameters") > lib.indexOf("-opt-in=kotlinx.cinterop.ExperimentalForeignApi"), lib.toString())
        assertTrue(lib.indexOf("-Xcontext-parameters") < lib.indexOf("-o"), lib.toString())
        val prog = p.programArgs(File(tmp, "x.kexe"), null)
        // #137: -Werror reaches the klib compiles but not the split-scheme package/patch compile (its scheme always warns);
        // the other modes stay
        assertTrue("-Werror" in lib && "-Xexplicit-api=strict" in lib, lib.toString())
        assertFalse("-Werror" in prog, prog.toString())
        assertTrue("-progressive" in prog && "-Xexplicit-api=strict" in prog && "-Xcompilation-scheme=split" in prog, prog.toString())
        assertTrue("-Werror" in p.libraryArgs(app), "the app klib (classifier, packaging) keeps -Werror")
        assertEquals(listOf("2.1"), prog.withIndex().filter { (i, _) -> i > 0 && prog[i - 1] == "-language-version" }.map { it.value })
        // a version the compiler does not know stops the compile, before anything is built
        val bad = Pipeline(s.copy(modules = listOf(app.copy(compilerArgs = listOf("-language-version", "2.7")), core)), Logging.getLogger("t"))
        val e = assertThrows<org.gradle.api.GradleException> { bad.programArgs(File(tmp, "x.kexe"), null) }
        assertTrue(":app: -language-version 2.7" in e.message!!, e.message)
    }

    @Test
    fun `the arguments are part of the build identity`() {
        val m = HrModule(":core", ModuleKind.LIBRARY, "/c", emptyList())
        fun line(args: List<String>) = "compilerArgs " + listOf(m.copy(compilerArgs = args)).filter { it.compilerArgs.isNotEmpty() }
            .joinToString(" ") { "${it.path}=${it.compilerArgs.joinToString(",")}" }.ifEmpty { "-" }
        assertFalse(line(emptyList()) == line(listOf("-Xcontext-parameters")))
        // a change at the end of a long line shows (the message used to cut both values to the same first 40 characters)
        val before = ":core=-opt-in=kotlin.ExperimentalStdlibApi,-opt-in=org.example.core.CoreOptApi,-Xcontext-parameters"
        val (a, b) = BuildIdentity.differingParts(before, "$before,-opt-in=org.example.core.CoreWarnApi")
        assertEquals("…(nothing)", a)
        assertEquals("…,-opt-in=org.example.core.CoreWarnApi", b)
        assertEquals("2.4.21" to "2.5.0", BuildIdentity.differingParts("2.4.21", "2.5.0"))
    }

    abstract class FakeCompile : DefaultTask() {
        abstract class Options {
            abstract val freeCompilerArgs: ListProperty<String>
            abstract val optIn: ListProperty<String>
            abstract val languageVersion: Property<FakeVersion>
            abstract val apiVersion: Property<FakeVersion>
            abstract val progressiveMode: Property<Boolean>
            abstract val allWarningsAsErrors: Property<Boolean>
            abstract val suppressWarnings: Property<Boolean>
        }
        class Settings { val enabledLanguageFeatures: Set<String> = setOf("ContextParameters") }

        @get:org.gradle.api.tasks.Internal
        val compilerOptions: Options = project.objects.newInstance(Options::class.java)

        @get:org.gradle.api.tasks.Internal
        val languageSettings = Settings()
    }

    enum class FakeVersion(val version: String) { KOTLIN_2_1("2.1") }

    @Test
    fun `the settings are read from the compile task through reflection (KGP's getter names)`() {
        val p = ProjectBuilder.builder().withProjectDir(File(tmp, "p").apply { mkdirs() }).build()
        val t = p.tasks.register(Kmp.COMPILE_TASK, FakeCompile::class.java).get()
        t.compilerOptions.freeCompilerArgs.set(listOf("-Xcontext-parameters"))
        t.compilerOptions.optIn.set(listOf("kotlin.ExperimentalStdlibApi"))
        t.compilerOptions.languageVersion.set(FakeVersion.KOTLIN_2_1)
        t.compilerOptions.progressiveMode.set(true)
        val r = Kmp.projectCompilerArgs(p)!!
        assertNull(r.reason)
        assertEquals(listOf("-language-version", "2.1", "-progressive", "-opt-in", "kotlin.ExperimentalStdlibApi", "-XXLanguage:+ContextParameters",
            "-Xcontext-parameters"), r.args)
        // a project without that task: nothing to read; one whose task has no compilerOptions: said so
        assertNull(Kmp.projectCompilerArgs(ProjectBuilder.builder().withProjectDir(File(tmp, "q").apply { mkdirs() }).build()))
        val q = ProjectBuilder.builder().withProjectDir(File(tmp, "r").apply { mkdirs() }).build()
        q.tasks.register(Kmp.COMPILE_TASK)
        assertTrue(Kmp.projectCompilerArgs(q)!!.reason!!.contains("no compilerOptions"))
    }
}
