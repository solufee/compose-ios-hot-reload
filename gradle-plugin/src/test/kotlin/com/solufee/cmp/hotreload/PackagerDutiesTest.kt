package com.solufee.cmp.hotreload

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** One check per packager duty of INTERFACE §3.3 (v3). #6 and the bundle side of #1/#5 run on every package (verifyBundle). */
class PackagerDutiesTest {
    private val logger = Logging.getLogger("test")

    private fun settings(keep: Boolean = true) = HrSettings(
        rootDir = "/p", workDir = "/p/build/hotreload", dist = "/d", kotlinc = "/d/bin/kotlinc-native", compilerJar = "/d/c.jar",
        composePlugin = "/c.jar", klibList = "/k.txt", kaldoDylib = "/l/libKaldo.iossim.dylib", kaldoSha256 = "x", target = "ios_simulator_arm64",
        orcRuntime = "/o.a", objcUtilsLib = "/u.a", llvmHome = null, useDaemon = true, socketDir = "/s", javaHome = "/j",
        modules = listOf(HrModule(":app", ModuleKind.APP, "/p/app/src", listOf(":ui")), HrModule(":ui", ModuleKind.LIBRARY, "/p/ui/src", emptyList())),
        infoPlist = "/p/app/Info.plist", linkerOptions = emptyList(), keepCompactUnwind = keep, port = 5568, udid = "booted",
        launchLog = "/p/launch.log", readyMarker = null, launchTimeoutSec = 120, launchRetries = 3, reinstall = true, warmRestart = true,
    )

    @Test
    fun `1 merge-payloads gets KALDO_KEEP_COMPACT_UNWIND=1 by default`() {
        val env = Pipeline(settings(), logger).mergeEnvironment(listOf("expui"))
        assertEquals("1", env["KALDO_KEEP_COMPACT_UNWIND"])
        assertEquals("expui", env["KALDO_HOT_PACKAGES"])
        assertEquals("/p/build/hotreload/merge-cache", env["KALDO_MERGE_CACHE_DIR"])
        assertFalse("KALDO_KEEP_COMPACT_UNWIND" in Pipeline(settings(keep = false), logger).mergeEnvironment(emptyList()))
    }

    @Test
    fun `2 and 3 the app is launched with SYNTH_EH_FRAME and the narrowed hot symbols`() {
        val s = settings()
        val env = Launcher(s, logger).appEnvironment()
        assertEquals("1", env["SIMCTL_CHILD_KALDO_SYNTH_EH_FRAME"])
        assertEquals(s.hotSymbols.path, env["SIMCTL_CHILD_KALDO_HOT_SYMBOLS"])
        assertEquals("5568", env["SIMCTL_CHILD_KONAN_HOT_RELOAD_PORT"])
    }

    @Test
    fun `4 the host-link compile never runs cache-only, even if the caller's shell sets it`() {
        val p = Pipeline(settings(), logger)
        assertFalse("KONAN_HOT_RELOAD_CACHE_ONLY" in p.compileEnvironment(cacheOnly = false))
        assertEquals("1", p.compileEnvironment(cacheOnly = true)["KONAN_HOT_RELOAD_CACHE_ONLY"])
        assertTrue("KONAN_HOT_RELOAD_CACHE_ONLY" in Proc.SCRUB)
    }

    @Test
    fun `5 absolute rpaths are found in otool output`() {
        val otool = """
            Load command 20
                      cmd LC_RPATH
                  cmdsize 64
                     path /opt/llvm/install-macos/lib (offset 12)
            Load command 21
                      cmd LC_RPATH
                  cmdsize 48
                     path @executable_path/Frameworks (offset 12)
        """.trimIndent()
        assertEquals(listOf("/opt/llvm/install-macos/lib", "@executable_path/Frameworks"), Pipeline.rpaths(otool))
    }
}
