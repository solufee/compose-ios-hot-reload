package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** #21: a library klib is compiled only when what it is built from changed, and the log says why. */
class KlibInputsTest {
    @TempDir
    lateinit var tmp: File

    private fun settings(modules: List<HrModule>, extraKlibs: List<String> = emptyList()): HrSettings {
        val klibList = File(tmp, "klibs.txt").apply { writeText("# none\n") }
        return HrSettings(
            rootDir = tmp.path, workDir = File(tmp, "w").path, dist = File(tmp, "dist").path, kotlinc = "/k", compilerJar = "/c.jar",
            composePlugin = "/p.jar", klibList = klibList.path, kaldoDylib = "/l.dylib", kaldoSha256 = null, target = "ios_simulator_arm64",
            orcRuntime = "/o.a", objcUtilsLib = "/u.a", llvmHome = null, useDaemon = false, socketDir = "/s", javaHome = "/j",
            modules = modules, infoPlist = "/p", linkerOptions = emptyList(), keepCompactUnwind = true, port = 5567, udid = "booted",
            launchLog = "/l", readyMarker = null, launchTimeoutSec = 1, launchRetries = 1, reinstall = true, warmRestart = true,
            extraKlibs = extraKlibs,
        )
    }

    private fun module(name: String, kind: ModuleKind, deps: List<String> = emptyList()): HrModule {
        val dir = File(tmp, "$name/src").apply { mkdirs() }
        File(dir, "$name.kt").writeText("package $name\n\nfun $name(): String = \"$name-1\"\n")
        return HrModule(":$name", kind, dir.path, deps)
    }

    @Test
    fun `inputs change with a source edit or an upstream klib, not with the app or an unchanged rewrite`() {
        val base = module("base", ModuleKind.LIBRARY)
        val core = module("core", ModuleKind.LIBRARY, listOf(":base"))
        val app = module("app", ModuleKind.APP, listOf(":core"))
        val s = settings(listOf(app, base, core))
        val p = Pipeline(s, org.gradle.api.logging.Logging.getLogger("t"))
        s.klibOf(base).apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val core0 = p.libraryInputs(core)

        // The app's sources are not an input of core.
        File(app.srcDir, "app.kt").writeText("package app\n\nfun app(): String = \"app-2\"\n")
        assertEquals(core0, p.libraryInputs(core))
        // Saving a file with the same content (new mtime) changes nothing.
        File(core.srcDir, "core.kt").apply { writeText(readText()); setLastModified(System.currentTimeMillis() + 5000) }
        assertEquals(core0, p.libraryInputs(core))
        // An upstream module whose klib bytes are the same (deterministic rebuild) changes nothing.
        s.klibOf(base).writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(core0, p.libraryInputs(core))

        // Upstream klib changed: core is rebuilt, and the reason names it.
        s.klibOf(base).writeBytes(byteArrayOf(1, 2, 4))
        val core1 = p.libraryInputs(core)
        assertNotEquals(core0, core1)
        assertEquals("module klibs changed: base", Pipeline.klibChangeReason(core0, core1))

        // A source edit of core.
        File(core.srcDir, "core.kt").writeText("package core\n\nfun core(): String = \"core-2\"\n")
        File(core.srcDir, "more.kt").writeText("package core\n\nfun more() = 1\n")
        val core2 = p.libraryInputs(core)
        assertEquals("sources changed: core.kt, more.kt", Pipeline.klibChangeReason(core1, core2))
        assertEquals("no previous build", Pipeline.klibChangeReason(null, core2))
    }

    @Test
    fun `a changed dependency klib from Gradle is named`() {
        val lib = module("lib", ModuleKind.LIBRARY)
        val app = module("app", ModuleKind.APP, listOf(":lib"))
        val dep = File(tmp, "deps/datetime.klib").apply { parentFile.mkdirs(); writeBytes(ByteArray(10)) }
        val s = settings(listOf(app, lib), listOf(dep.path))
        val p = Pipeline(s, org.gradle.api.logging.Logging.getLogger("t"))
        val a = p.libraryInputs(lib)
        dep.writeBytes(ByteArray(12))
        assertEquals("dependency klibs changed: datetime.klib", Pipeline.klibChangeReason(a, p.libraryInputs(lib)))
    }
}
