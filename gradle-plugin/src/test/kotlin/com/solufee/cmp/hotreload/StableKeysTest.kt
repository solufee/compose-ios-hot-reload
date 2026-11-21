package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** #47: HotReloadStableKeys goes into every hot-reload compile when the Compose compiler knows it, never into release. */
class StableKeysTest {
    @TempDir
    lateinit var tmp: File

    private val option = "plugin:androidx.compose.compiler.plugins.kotlin:featureFlag=HotReloadStableKeys"

    private fun jar(name: String, featureFlagClass: String?): File = File(tmp, name).also { f ->
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); z.write("Manifest-Version: 1.0\n".toByteArray()); z.closeEntry()
            if (featureFlagClass != null) {
                z.putNextEntry(ZipEntry("androidx/compose/compiler/plugins/kotlin/FeatureFlag.class"))
                z.write(featureFlagClass.toByteArray(Charsets.ISO_8859_1)); z.closeEntry()
            }
        }
    }

    private fun settings(plugin: File, stableKeys: Boolean = true): HrSettings {
        val src = File(tmp, "app/src").apply { mkdirs(); File(this, "main.kt").writeText("package app\n\nfun main() {}\n") }
        val lib = File(tmp, "ui/src").apply { mkdirs(); File(this, "ui.kt").writeText("package ui\n\nfun ui() = 1\n") }
        val plist = File(tmp, "Info.plist").apply {
            writeText("""<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>CFBundleExecutable</key><string>App</string>""" +
                """<key>CFBundleIdentifier</key><string>org.example.app</string></dict></plist>""")
        }
        return HrSettings(
            rootDir = tmp.path, workDir = File(tmp, "w").path, dist = File(tmp, "dist").path, kotlinc = "/k", compilerJar = "/c.jar",
            composePlugin = plugin.path, klibList = File(tmp, "klibs.txt").apply { writeText("# none\n") }.path, kaldoDylib = "/l.dylib",
            kaldoSha256 = null, target = "ios_simulator_arm64", orcRuntime = "/o.a", objcUtilsLib = "/u.a", llvmHome = null, useDaemon = false,
            socketDir = "/s", javaHome = "/j",
            modules = listOf(HrModule(":app", ModuleKind.APP, src.path, listOf(":ui")), HrModule(":ui", ModuleKind.LIBRARY, lib.path, emptyList())),
            infoPlist = plist.path, linkerOptions = emptyList(), keepCompactUnwind = true, port = 5567, udid = "booted", launchLog = "/l",
            readyMarker = null, launchTimeoutSec = 1, launchRetries = 1, reinstall = true, warmRestart = true, stableKeys = stableKeys,
        )
    }

    private val logger = org.gradle.api.logging.Logging.getLogger("t")

    @Test
    fun `the flag is detected in the Compose plugin jar`() {
        assertTrue(Pipeline.composePluginKnows(jar("c9.jar", "...HotReloadStableKeys...StrongSkipping..."), "HotReloadStableKeys"))
        assertFalse(Pipeline.composePluginKnows(jar("c8.jar", "...StrongSkipping...OptimizeNonSkippingGroups..."), "HotReloadStableKeys"))
        assertFalse(Pipeline.composePluginKnows(jar("none.jar", null), "HotReloadStableKeys"))
        assertFalse(Pipeline.composePluginKnows(File(tmp, "missing.jar"), "HotReloadStableKeys"))
    }

    @Test
    fun `hot-reload compiles get the flag when the compiler knows it, release compiles never`() {
        val c9 = jar("c9.jar", "HotReloadStableKeys")
        val s = settings(c9)
        val p = Pipeline(s, logger)
        assertEquals("on", p.stableKeysState)
        val ui = s.modules.single { it.kind == ModuleKind.LIBRARY }
        for (args in listOf(p.libraryArgs(ui), p.libraryArgs(s.app, File(tmp, "compat/app.klib")), p.programArgs(File(tmp, "a.kexe"), null))) {
            val i = args.indexOf(option)
            assertTrue(i > 0 && args[i - 1] == "-P", args.joinToString(" "))
        }
        val release = ReleaseCheck(s, logger).commands(File(tmp, "rel")).flatten()
        assertFalse(release.any { it.contains("HotReloadStableKeys") }, release.joinToString(" "))
    }

    @Test
    fun `no flag when it is turned off or the compiler does not know it`() {
        val off = Pipeline(settings(jar("c9.jar", "HotReloadStableKeys"), stableKeys = false), logger)
        assertEquals("off", off.stableKeysState)
        assertTrue(off.programArgs(File(tmp, "a.kexe"), null).none { it.contains("HotReloadStableKeys") })
        val c8 = Pipeline(settings(jar("c8.jar", "StrongSkipping")), logger)
        assertEquals("unsupported", c8.stableKeysState)
        assertTrue(c8.programArgs(File(tmp, "a.kexe"), null).none { it.contains("HotReloadStableKeys") })
    }

    @Test
    fun `#52 - splitPayloadList is detected in the compiler jar, used only by the package compile, never by release`() {
        fun compilerJar(name: String, binaryOptions: String?): File = File(tmp, name).also { f ->
            ZipOutputStream(f.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); z.write("Manifest-Version: 1.0\n".toByteArray()); z.closeEntry()
                if (binaryOptions != null) {
                    z.putNextEntry(ZipEntry("org/jetbrains/kotlin/config/nativeBinaryOptions/BinaryOptions.class"))
                    z.write(binaryOptions.toByteArray(Charsets.ISO_8859_1)); z.closeEntry()
                }
            }
        }
        assertTrue(Pipeline.compilerKnowsSplitPayloadList(compilerJar("k9.jar", "...splitPayloadList...gcSchedulerType...")))
        assertFalse(Pipeline.compilerKnowsSplitPayloadList(compilerJar("k8.jar", "...gcSchedulerType...")))
        assertFalse(Pipeline.compilerKnowsSplitPayloadList(compilerJar("none.jar", null)))
        assertFalse(Pipeline.compilerKnowsSplitPayloadList(File(tmp, "missing.jar")))

        val s = settings(jar("c9.jar", "HotReloadStableKeys")).copy(compilerJar = compilerJar("k9b.jar", "splitPayloadList").path)
        val p = Pipeline(s, logger)
        assertTrue(p.splitPayloads)
        // The reload (patch) compile shares its arguments with the package compile; only the package adds the option.
        val reload = p.programArgs(File(tmp, "a.kexe"), File(tmp, "caches.txt"))
        assertTrue(reload.none { it.contains("splitPayloadList") }, reload.joinToString(" "))
        val pkg = p.programArgs(File(tmp, "a.kexe"), null, listOf("-Xbinary=splitPayloadList=/x/payloads.txt"))
        assertEquals(pkg.indexOf("-o") - 1, pkg.indexOf("-Xbinary=splitPayloadList=/x/payloads.txt"), pkg.joinToString(" "))
        val release = ReleaseCheck(s, logger).commands(File(tmp, "rel")).flatten()
        assertFalse(release.any { it.contains("splitPayloadList") }, release.joinToString(" "))
    }

    @Test
    fun `the HOT note follows the setting`() {
        val line = "NOTE remember-reset Badge: composable signature changed"
        assertTrue(hotNote(line, stableKeys = true).contains("its remember state is kept"), hotNote(line, true))
        assertTrue(hotNote(line, stableKeys = false).contains("resets once"), hotNote(line, false))
    }
}
