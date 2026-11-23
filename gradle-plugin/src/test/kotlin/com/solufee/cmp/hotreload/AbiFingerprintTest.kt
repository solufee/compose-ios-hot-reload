package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * #124: a library module counts its upstream modules by ABI fingerprint (metadata + inline IR), so a body-only edit
 * upstream does not recompile it; any failure to compute the fingerprint falls back to the klib bytes.
 */
class AbiFingerprintTest {
    @TempDir
    lateinit var tmp: File

    private fun settings(modules: List<HrModule>, dist: String = File(tmp, "dist").path, abi: Boolean = true): HrSettings {
        val klibList = File(tmp, "klibs.txt").apply { writeText("# none\n") }
        return HrSettings(
            rootDir = tmp.path, workDir = File(tmp, "w").path, dist = dist, kotlinc = "/k", compilerJar = "/c.jar",
            composePlugin = "/p.jar", klibList = klibList.path, kaldoDylib = "/l.dylib", kaldoSha256 = null, target = "ios_simulator_arm64",
            orcRuntime = "/o.a", objcUtilsLib = "/u.a", llvmHome = null, useDaemon = false, socketDir = File(tmp, "s").path,
            javaHome = System.getProperty("java.home"),
            modules = modules, infoPlist = "/p", linkerOptions = emptyList(), keepCompactUnwind = true, port = 5567, udid = "booted",
            launchLog = "/l", readyMarker = null, launchTimeoutSec = 1, launchRetries = 1, reinstall = true, warmRestart = true,
            abiFingerprint = abi,
        )
    }

    private fun module(name: String, kind: ModuleKind, deps: List<String> = emptyList()): HrModule {
        val dir = File(tmp, "$name/src").apply { mkdirs() }
        File(dir, "$name.kt").writeText("package $name\n\nfun $name(): String = \"$name-1\"\n")
        return HrModule(":$name", kind, dir.path, deps)
    }

    private class Setup(val s: HrSettings, val p: Pipeline, val base: HrModule, val core: HrModule)

    private fun setup(abi: Boolean = true): Setup {
        val base = module("base", ModuleKind.LIBRARY)
        val core = module("core", ModuleKind.LIBRARY, listOf(":base"))
        val app = module("app", ModuleKind.APP, listOf(":core"))
        val s = settings(listOf(app, base, core), abi = abi)
        return Setup(s, Pipeline(s, org.gradle.api.logging.Logging.getLogger("t")), base, core)
    }

    private fun fp(c: Char) = c.toString().repeat(64)

    @Test
    fun `body-only upstream change keeps the inputs, an ABI change rebuilds and names the module`() {
        val t = setup()
        // Fake fingerprints by klib content: [1,2,3] and [1,2,4] differ in bytes only (a body edit), [1,2,5] in ABI.
        val calls = mutableListOf<String>()
        t.p.abiFingerprintCommand = { k ->
            val b = k.readBytes().toList()
            calls += b.joinToString(",")
            if (b.last() == 5.toByte()) fp('b') else fp('a')
        }
        val klib = t.s.klibOf(t.base).apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val core0 = t.p.libraryInputs(t.core)
        assertTrue(core0.contains("module base abi ${fp('a')}"), core0.toString())

        klib.writeBytes(byteArrayOf(1, 2, 4))
        assertEquals(core0, t.p.libraryInputs(t.core), "body-only upstream change: downstream skipped")

        klib.writeBytes(byteArrayOf(1, 2, 5))
        val core1 = t.p.libraryInputs(t.core)
        assertNotEquals(core0, core1, "ABI change upstream: downstream recompiled")
        assertEquals("module klibs changed: base", Pipeline.klibChangeReason(core0, core1))

        // Remembered per klib sha256: asking again for the same bytes does not run the tool again.
        val n = calls.size
        t.p.libraryInputs(t.core)
        assertEquals(n, calls.size)
        assertEquals("${Tools.sha256(byteArrayOf(1, 2, 5))} ${fp('b')}", File(klib.parentFile, klib.name + ".abi").readText().trim())
    }

    @Test
    fun `any failure falls back to the klib bytes, which also rebuilds`() {
        val t = setup()
        val klib = t.s.klibOf(t.base).apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        t.p.abiFingerprintCommand = { fp('a') }
        val good = t.p.libraryInputs(t.core)

        for (bad in listOf<(File) -> String>({ throw IllegalStateException("klib dump failed") }, { "not a fingerprint" }, { "" })) {
            klib.writeBytes(byteArrayOf(1, 2, 4))
            t.p.abiFingerprintCommand = bad
            val fallback = t.p.libraryInputs(t.core)
            assertTrue(fallback.contains("module base sha256 ${Tools.sha256(byteArrayOf(1, 2, 4))}"), fallback.toString())
            assertNotEquals(good, fallback, "switching to the fallback recompiles (never skips on a guess)")
            assertEquals("module klibs changed: base", Pipeline.klibChangeReason(good, fallback))
            assertTrue(!File(klib.parentFile, klib.name + ".abi").exists(), "no memo after a failure")
        }
    }

    @Test
    fun `abiFingerprint false uses the klib bytes and never runs the tool`() {
        val t = setup(abi = false)
        t.p.abiFingerprintCommand = { throw AssertionError("must not run") }
        t.s.klibOf(t.base).apply { parentFile.mkdirs(); writeBytes(byteArrayOf(9)) }
        assertTrue(t.p.libraryInputs(t.core).contains("module base sha256 ${Tools.sha256(byteArrayOf(9))}"))
    }

    @Test
    fun `a missing upstream klib is named missing`() {
        val t = setup()
        t.p.abiFingerprintCommand = { throw AssertionError("must not run") }
        assertTrue(t.p.libraryInputs(t.core).contains("module base missing"))
    }

    /**
     * The bundled compat-classify.py on real klibs of the pinned toolchain (skipped without -Pcmp.hotreload.toolchainDir):
     * a body-only change keeps the fingerprint; inline body, signature, const value, private member, sealed subclass and
     * enum entry order change it. (Compose / serialization / Koin variants: evidence/abi-fingerprint-124/.)
     */
    @Test
    fun `real klibs - body only keeps the fingerprint, ABI changes change it`() {
        val dir = System.getProperty("cmp.hotreload.toolchainDir")
        assumeTrue(dir != null && File(dir, TOOLCHAIN_MANIFEST).isFile, "no toolchain configured; real fingerprint test skipped")
        val kotlinc = File(dir!!, "bin/kotlinc-native").path
        val javaHome = System.getProperty("java.home")
        val lib = module("lib", ModuleKind.LIBRARY)
        val s = settings(listOf(module("app", ModuleKind.APP, listOf(":lib")), lib), dist = dir)
        val p = Pipeline(s, org.gradle.api.logging.Logging.getLogger("t"))

        fun fingerprint(name: String, source: String): String {
            val src = File(tmp, "v/$name/lib.kt").apply { parentFile.mkdirs(); writeText(source) }
            val out = File(tmp, "v/$name/lib")
            Proc.check(listOf(kotlinc, "-target", "ios_simulator_arm64", "-p", "library", "-g", src.path, "-o", out.path),
                env = mapOf("JAVA_HOME" to javaHome), echo = false)
            val k = File(out.path + ".klib")
            return p.abiFingerprint(k, Tools.sha256(k.readBytes())) ?: error("no fingerprint for $name")
        }
        val head = "package p\nconst val LIMIT: Int = 3\ninline fun twice(x: Int): Int = x * 2\nsealed interface S\nclass A : S\nenum class E { X, Y }\n"
        val base = fingerprint("base", head + "class Box(val v: Int) { private fun h(): Int = v; fun get(): Int = h() + 1 }\n")
        assertEquals(base, fingerprint("body", head + "class Box(val v: Int) { private fun h(): Int = v; fun get(): Int = h() + 2 }\n"))
        val changed = mapOf(
            "inline" to head.replace("x * 2", "x * 3"),
            "signature" to head + "class Box(val v: Long)",
            "const" to head.replace("= 3", "= 4"),
            "private" to head + "class Box(val v: Int) { private fun h(): Long = 1L; fun get(): Int = h().toInt() + 1 }\n",
            "sealed" to head + "class B : S\n",
            "enum" to head.replace("X, Y", "Y, X"),
        )
        for ((name, src) in changed) {
            val extra = if (name in setOf("inline", "const", "enum")) "class Box(val v: Int) { private fun h(): Int = v; fun get(): Int = h() + 1 }\n" else ""
            assertNotEquals(base, fingerprint(name, src + extra), name)
        }
    }
}
