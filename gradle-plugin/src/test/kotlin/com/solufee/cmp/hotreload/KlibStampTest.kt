package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

/** #129: the reload's library pass skips a module only when the metadata of every compile input is unchanged. */
class KlibStampTest {
    @TempDir
    lateinit var tmp: File

    private val logger = Logging.getLogger("t")

    private fun mod(name: String, deps: List<String> = emptyList(), options: List<String> = emptyList()): HrModule {
        val dir = File(tmp, "$name/src/commonMain/kotlin").apply { mkdirs() }
        return HrModule(":$name", ModuleKind.LIBRARY, dir.path, deps.map { ":$it" }, pluginOptions = options, buildDir = File(tmp, "$name/build").path)
    }

    private fun settings(vararg libs: HrModule, global: List<String> = emptyList()): HrSettings {
        val app = HrModule(":app", ModuleKind.APP, File(tmp, "app/src").apply { mkdirs() }.path, libs.map { it.path })
        return HrSettings(
            rootDir = tmp.path, workDir = File(tmp, "work").path, dist = "/d", kotlinc = "/d/bin/kotlinc-native", compilerJar = "/d/c.jar",
            composePlugin = "/nonexistent/compose.jar", klibList = File(tmp, "klibs.txt").apply { writeText("") }.path, kaldoDylib = "/l.dylib", kaldoSha256 = "x", target = "ios_simulator_arm64",
            orcRuntime = "/o.a", objcUtilsLib = "/u.a", llvmHome = null, useDaemon = true, socketDir = "/s", javaHome = "/j",
            modules = listOf(app) + libs, infoPlist = "/p/Info.plist", linkerOptions = emptyList(), keepCompactUnwind = true, port = 5568,
            udid = "booted", launchLog = "/p/launch.log", readyMarker = null, launchTimeoutSec = 120, launchRetries = 3, reinstall = true,
            warmRestart = true,
            moduleBuildFiles = libs.associate { it.path to listOf(File(tmp, "${it.path.trim(':')}/build.gradle.kts").apply { writeText("// ${it.path}\n") }.path) },
            globalBuildFiles = global,
        )
    }

    private fun src(m: HrModule, name: String, text: String) = File(m.srcDir, name).apply { writeText(text) }

    @Test
    fun `the stamp is stable while nothing changes, and changes with each input`() {
        val core = mod("core")
        val s = settings(core)
        val p = Pipeline(s, logger)
        val a = src(core, "A.kt", "package core\nfun a() = 1\n")
        val s0 = p.libraryStamp(core)
        assertEquals(s0, p.libraryStamp(core), "nothing changed")
        // a new file, then its removal
        val b = src(core, "B.kt", "package core\nfun b() = 2\n")
        assertNotEquals(s0, p.libraryStamp(core), "a source file was added")
        b.delete()
        assertEquals(s0.filterNot { it.startsWith("args") }, p.libraryStamp(core).filterNot { it.startsWith("args") }, "B.kt removed again: same files")
        // the module's build file
        val bf = File(s.moduleBuildFiles.getValue(":core").single())
        val before = p.libraryStamp(core)
        bf.appendText("kotlin { compilerOptions { freeCompilerArgs.add(\"-Xfoo\") } }\n")
        assertNotEquals(before, p.libraryStamp(core), "build.gradle.kts changed")
        assertTrue(p.libraryStamp(core).any { it.startsWith("src ${a.path} ") })
    }

    @Test
    fun `content changed but mtime set back (touch -r) still changes the stamp, by ctime`() {
        val core = mod("core")
        val p = Pipeline(settings(core), logger)
        val a = src(core, "A.kt", "package core\nfun a() = 1\n")
        val mtime = Files.getLastModifiedTime(a.toPath())
        val before = p.libraryStamp(core)
        Thread.sleep(20)
        a.writeText("package core\nfun a() = 2\n")   // same size
        Files.setLastModifiedTime(a.toPath(), mtime)    // what touch -r / rsync -t / "keep timestamps" do
        assertEquals(mtime, Files.getLastModifiedTime(a.toPath()))
        assertNotEquals(before, p.libraryStamp(core), "ctime moved although size and mtime are as before")
    }

    @Test
    fun `a replaced file with the same size and mtime differs by inode`() {
        val core = mod("core")
        val p = Pipeline(settings(core), logger)
        val a = src(core, "A.kt", "package core\nfun a() = 1\n")
        val before = p.libraryStamp(core)
        val tmpCopy = File(tmp, "A.kt.new").apply { writeText("package core\nfun a() = 3\n") }
        Files.setLastModifiedTime(tmpCopy.toPath(), Files.getLastModifiedTime(a.toPath()))
        Files.move(tmpCopy.toPath(), a.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertNotEquals(before, p.libraryStamp(core))
    }

    @Test
    fun `compiler options from the build, plugin options, are in the stamp through the arguments`() {
        val a = Pipeline(settings(mod("core")), logger).libraryStamp(mod("core"))
        val b = Pipeline(settings(mod("core", options = listOf("plugin:x:y=1"))), logger).libraryStamp(mod("core", options = listOf("plugin:x:y=1")))
        assertNotEquals(a.first { it.startsWith("args") }, b.first { it.startsWith("args") })
    }

    @Test
    fun `generated sources under the build directory are stamped too`() {
        val gen = File(tmp, "core/build/generated/ksp/iosSimulatorArm64/kotlin").apply { mkdirs() }
        val core = mod("core").let { it.copy(fragments = it.fragments + HrFragment("gen", listOf(gen.path))) }
        val p = Pipeline(settings(core), logger)
        val before = p.libraryStamp(core)
        File(gen, "Gen.kt").writeText("package core\nval g = 1\n")
        assertNotEquals(before, p.libraryStamp(core))
    }

    @Test
    fun `settings, properties and version catalogs are in the global stamp`() {
        val toml = File(tmp, "gradle/libs.versions.toml").apply { parentFile.mkdirs(); writeText("[versions]\nk = \"1\"\n") }
        val p = Pipeline(settings(mod("core"), global = listOf(toml.path)), logger)
        val before = p.globalStamp()
        toml.writeText("[versions]\nk = \"2\"\n")
        assertNotEquals(before, p.globalStamp())
    }

    @Test
    fun `skip only with the recorded stamp, klib and record present, and nothing upstream compiled`() {
        val core = mod("core")
        val s = settings(core)
        val p = Pipeline(s, logger)
        src(core, "A.kt", "package core\nfun a() = 1\n")
        val stamp = p.libraryStamp(core)
        assertFalse(p.canSkip(core, stamp, false), "no klib, no record, no stamp")
        s.klibOf(core).apply { parentFile.mkdirs(); writeText("klib") }
        p.klibInputsFile(core).writeText("inputs\n")
        assertFalse(p.canSkip(core, stamp, false), "no stamp recorded yet")
        p.klibStampFile(core).writeText(stamp.joinToString("\n", postfix = "\n"))
        assertTrue(p.canSkip(core, stamp, false))
        assertFalse(p.canSkip(core, stamp, true), "a module it links was compiled in this pass")
        assertFalse(p.canSkip(core, stamp + "src x", false), "stamp differs")
        p.klibInputsFile(core).delete()
        assertFalse(p.canSkip(core, stamp, false), "input record missing")
    }

    @Test
    fun `the stamp follows the klibs and records of the linked modules`() {
        val core = mod("core")
        val feature = mod("feature", deps = listOf("core"))
        val s = settings(core, feature)
        val p = Pipeline(s, logger)
        s.klibOf(core).apply { parentFile.mkdirs(); writeText("v1") }
        val before = p.libraryStamp(feature)
        Thread.sleep(20)
        s.klibOf(core).writeText("v2")
        assertNotEquals(before, p.libraryStamp(feature), "upstream klib rewritten")
    }

    @Test
    fun `library order puts every module after the modules it links, a cycle is an error`() {
        val core = mod("core")
        val domain = mod("domain", deps = listOf("core"))
        val feature = mod("feature", deps = listOf("domain", "core"))
        val order = Pipeline(settings(feature, domain, core), logger).libraryOrder().map { it.path }
        assertEquals(listOf(":core", ":domain", ":feature"), order)
        val a = mod("a", deps = listOf("b"))
        val b = mod("b", deps = listOf("a"))
        assertThrows<GradleException> { Pipeline(settings(a, b), logger).libraryOrder() }
    }

    @Test
    fun `the kotlin-profile check does not count the directory again once it warned in a session`() {
        var counted = 0
        val key = "watch:" + tmp.path
        assertEquals("w", KotlinProfileHint.onceLazy(key) { counted++; "w" })
        assertNull(KotlinProfileHint.onceLazy(key) { counted++; "w" })
        assertNull(KotlinProfileHint.onceLazy(key) { counted++; "w" })
        assertEquals(1, counted, "listed once, not on every round")
        assertNull(KotlinProfileHint.onceLazy("$key-quiet") { counted++; null })
        assertEquals("w", KotlinProfileHint.onceLazy("$key-quiet") { counted++; "w" }, "nothing to say yet: asked again")
    }
}
