package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Kotlin Multiplatform support (0.2.0): versions, dependency klib selection, entry detection, sources. */
class KmpTest {
    @TempDir
    lateinit var tmp: File

    private fun klib(name: String, uniqueName: String, abi: String = "2.3.0", dir: Boolean = false): File {
        val text = "unique_name=${uniqueName.replace(":", "\\:")}\nabi_version=$abi\n"
        return if (dir) {
            File(tmp, "$name/default").apply { mkdirs() }.let { File(it, "manifest").writeText(text) }
            File(tmp, name)
        } else {
            File(tmp, "$name.klib").also { f ->
                ZipOutputStream(f.outputStream()).use { z ->
                    z.putNextEntry(ZipEntry("default/manifest"))
                    z.write(text.toByteArray())
                    z.closeEntry()
                }
            }
        }
    }

    @Test
    fun `versions compare numerically, a pre-release before its release`() {
        assertTrue(Kmp.compareVersions("1.12.2", "1.13.0-beta01") < 0)
        assertTrue(Kmp.compareVersions("1.13.0", "1.13.0-beta01") > 0)
        assertTrue(Kmp.compareVersions("1.13.0-beta02", "1.13.0-beta01") > 0)
        assertEquals(0, Kmp.compareVersions("2.3", "2.3.0"))
        assertTrue(Kmp.compareVersions("2.5.0", "2.3.0") > 0)
    }

    @Test
    fun `each Compose module is compared with the toolchain's copy of that module, in its own version scheme`() {
        val stdlib = klib("stdlib", "stdlib", abi = "2.5.0", dir = true)
        File(tmp, "tc/androidx.compose.runtime/runtime-iossimulatorarm64/1.13.0-beta01").mkdirs()
        val tcRuntime = klib("tc/androidx.compose.runtime/runtime-iossimulatorarm64/1.13.0-beta01/rt", "androidx.compose.runtime:runtime")
        val tcFoundation = klib("tc-foundation", "org.jetbrains.compose.foundation:foundation", dir = true)   // compose-klibs/: the fork's version
        val tc = listOf(tcRuntime.path, tcFoundation.path)
        fun dep(id: String, unique: String, via: String? = null) = Kmp.Dependency(id, klib(id.replace(':', '_'), unique), via)
        val fine = Kmp.selectKlibs(tc, stdlib, listOf(
            dep("androidx.compose.runtime:runtime-iossimulatorarm64:1.12.0", "androidx.compose.runtime:runtime"),
            dep("org.jetbrains.compose.foundation:foundation-iossimulatorarm64:1.12.2", "org.jetbrains.compose.foundation:foundation"),
            dep("org.jetbrains.compose.material3:material3-iossimulatorarm64:1.14.0", "org.jetbrains.compose.material3:material3"),  // not in the toolchain: not judged
        ), "1.13.0-beta01", null)
        assertEquals(2, fine.replaced.size)
        val e = assertThrows<GradleException> {
            Kmp.selectKlibs(tc, stdlib, listOf(
                dep("org.jetbrains.compose.foundation:foundation-iossimulatorarm64:1.14.0", "org.jetbrains.compose.foundation:foundation", via = "org.example:charts:2.0"),
            ), "1.13.0-beta01", null)
        }
        assertTrue("org.jetbrains.compose.foundation:foundation-iossimulatorarm64:1.14.0 (pulled in by org.example:charts:2.0): Compose module " +
            "org.jetbrains.compose.foundation:foundation 1.14.0 is newer than the toolchain's copy (1.13.0-beta01)" in e.message!!, e.message)
        assertTrue("or a release of org.example:charts:2.0 built against it" in e.message!!, e.message)
    }

    @Test
    fun `cinterop klibs among the dependencies are reported`() {
        val stdlib = klib("stdlib", "stdlib", abi = "2.5.0", dir = true)
        val f = File(tmp, "native.klib")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("default/manifest"))
            z.write("unique_name=org.example\\:native-cinterop-sqlite\nabi_version=2.5.0\ninterop=true\nlinkerOpts=-lsqlite3\n".toByteArray())
            z.closeEntry()
        }
        val sel = Kmp.selectKlibs(emptyList(), stdlib, listOf(Kmp.Dependency("org.example:native-iossimulatorarm64:1.0", f, "org.example:db:1.0")), "1.13.0-beta01", null)
        assertEquals(listOf("org.example:native-iossimulatorarm64:1.0 (pulled in by org.example:db:1.0) [linkerOpts -lsqlite3]"), sel.cinterop)
        assertEquals(listOf(f.path), sel.klibs)
    }

    @Test
    fun `toolchain components replace the dependency's, all klibs of a component, and duplicates are dropped`() {
        val stdlib = klib("stdlib", "stdlib", abi = "2.5.0", dir = true)
        val tcUi = klib("tc-ui-uikit", "org.jetbrains.compose.ui:ui-uikit", dir = true)
        val depUi = klib("dep-ui-uikit", "org.jetbrains.compose.ui:ui-uikit")
        val depUiInterop = klib("dep-ui-uikit-cinterop", "org.jetbrains.compose.ui:ui-uikit-cinterop-utils")
        val res = klib("components-resources", "components.resources:library")
        val res2 = klib("components-resources-2", "components.resources:library")
        val sel = Kmp.selectKlibs(
            listOf(tcUi.path), stdlib,
            listOf(
                Kmp.Dependency("org.jetbrains.compose.ui:ui-uikit-iossimulatorarm64:1.12.2", depUi),
                Kmp.Dependency("org.jetbrains.compose.ui:ui-uikit-iossimulatorarm64:1.12.2", depUiInterop),
                Kmp.Dependency("org.jetbrains.compose.components:components-resources-iosSimulatorArm64:1.12.2", res),
                Kmp.Dependency("other:dup:1.0", res2),
            ),
            "1.13.0-beta01", null,
        )
        assertEquals(listOf(res.path), sel.klibs)
        assertEquals(2, sel.replaced.size)
        assertEquals(listOf("other:dup:1.0"), sel.duplicates)
        assertEquals(emptyList<String>(), sel.newer)
    }

    @Test
    fun `a dependency newer than the toolchain's copy that replaces it is reported`() {
        val stdlib = klib("stdlib", "stdlib", abi = "2.5.0", dir = true)
        File(tmp, "tc/androidx.lifecycle/lifecycle-common-iossimulatorarm64/2.9.4").mkdirs()
        val tc = klib("tc/androidx.lifecycle/lifecycle-common-iossimulatorarm64/2.9.4/lc", "androidx.lifecycle:lifecycle-common")
        val dep = klib("dep-lc", "androidx.lifecycle:lifecycle-common")
        val old = klib("dep-lc-old", "androidx.lifecycle:lifecycle-common")
        assertEquals(listOf("androidx.lifecycle:lifecycle-common-iossimulatorarm64 2.11.0 -> 2.9.4"),
            Kmp.selectKlibs(listOf(tc.path), stdlib, listOf(Kmp.Dependency("androidx.lifecycle:lifecycle-common-iossimulatorarm64:2.11.0", dep)), "1.13.0-beta01", null).newer)
        assertEquals(emptyList<String>(),
            Kmp.selectKlibs(listOf(tc.path), stdlib, listOf(Kmp.Dependency("androidx.lifecycle:lifecycle-common-iossimulatorarm64:2.9.0", old)), "1.13.0-beta01", null).newer)
    }

    @Test
    fun `a klib built for a newer ABI than the compiler stops the build with the library named`() {
        val stdlib = klib("stdlib", "stdlib", abi = "2.5.0", dir = true)
        val future = klib("future", "future:lib", abi = "2.7.0")
        val e = assertThrows<GradleException> {
            Kmp.selectKlibs(emptyList(), stdlib, listOf(Kmp.Dependency("org.example:future-iossimulatorarm64:9.0", future)), "1.13.0-beta01", null)
        }
        assertTrue("org.example:future-iossimulatorarm64:9.0: built for klib ABI 2.7.0" in e.message!!, e.message)
    }

    @Test
    fun `klib metadata newer than the compiler reads, and too old ABIs, are reported`() {
        val stdlib = File(tmp, "stdlib/default").apply { mkdirs() }.let { File(it, "manifest").writeText("unique_name=stdlib\nabi_version=2.5.0\nmetadata_version=2.5.0\n"); File(tmp, "stdlib") }
        val meta = File(tmp, "meta.klib").also { f ->
            ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("default/manifest")); z.write("unique_name=m\nabi_version=2.5.0\nmetadata_version=2.7.0\n".toByteArray()); z.closeEntry() }
        }
        val old = klib("old", "old:lib", abi = "1.4.1")
        val e = assertThrows<GradleException> {
            Kmp.selectKlibs(emptyList(), stdlib, listOf(Kmp.Dependency("a:meta:1", meta), Kmp.Dependency("b:old:1", old)), "1.13.0-beta01", null)
        }
        assertTrue("a:meta:1: klib metadata 2.7.0 is newer than the toolchain's compiler can read (up to 2.6.0)" in e.message!!, e.message)
        assertTrue("b:old:1: built for klib ABI 1.4.1, older than the oldest the compiler reads (1.6.0" in e.message!!, e.message)
    }

    @Test
    fun `the KMP template entry is found, an app with its own main has none`() {
        val ios = File(tmp, "iosMain/kotlin/org/example").apply { mkdirs() }
        File(ios, "MainViewController.kt").writeText("package org.example.kmp\n\nfun MainViewController() = ComposeUIViewController { App() }\n")
        assertEquals("org.example.kmp.MainViewController", Kmp.detectIosEntry(listOf(File(tmp, "iosMain/kotlin"))))
        File(ios, "main.kt").writeText("fun main(args: Array<String>) {}\n")
        assertNull(Kmp.detectIosEntry(listOf(File(tmp, "iosMain/kotlin"))))
    }

    @Test
    fun `several fragments give a multiplatform compilation, one fragment the 0_1 arguments`() {
        val common = File(tmp, "commonMain").apply { mkdirs() }.also { File(it, "a.kt").writeText("package p") }
        val ios = File(tmp, "iosMain").apply { mkdirs() }.also { File(it, "b.kt").writeText("package p") }
        val kmp = HrModule(":shared", ModuleKind.APP, common.path, emptyList(),
            listOf(HrFragment("commonMain", listOf(common.path)), HrFragment("iosMain", listOf(ios.path, File(tmp, "missing").path), listOf("commonMain"))))
        val p = Pipeline(settings(kmp), org.gradle.api.logging.Logging.getLogger("t"))
        val args = p.sourceArgs(kmp)
        assertEquals(listOf("-Xmulti-platform", "-Xexpect-actual-classes", "-Xfragments=commonMain,iosMain", "-Xfragment-refines=iosMain:commonMain",
            "-Xfragment-sources=commonMain:${File(common, "a.kt").path}", "-Xfragment-sources=iosMain:${File(ios, "b.kt").path}",
            File(common, "a.kt").path, File(ios, "b.kt").path), args)
        val plain = HrModule(":app", ModuleKind.APP, common.path, emptyList())
        assertEquals(listOf(common.path), Pipeline(settings(plain), org.gradle.api.logging.Logging.getLogger("t")).sourceArgs(plain))
    }

    @Test
    fun `objects are assigned to modules by any Kotlin symbol, file initializers included`() {
        val ui = HrModule(":ui", ModuleKind.LIBRARY, "/u", emptyList())
        val app = HrModule(":app", ModuleKind.APP, "/a", emptyList())
        val pkgs = mapOf(ui to setOf("kmphr.shared.generated.resources"), app to setOf("org.example"))
        assertEquals(ui, Pipeline.classify(setOf("__Konan_init_59afbfb0b603c12f:kmphr.shared.generated.resources.Res"), pkgs))
        assertEquals(app, Pipeline.classify(setOf("_kclass:org.example.Greeting"), pkgs))
        assertEquals(ui, Pipeline.classify(setOf("_kvar:kmphr.shared.generated.resources.Res.drawable#internal"), pkgs))
        assertNull(Pipeline.classify(setOf("_OBJC_CLASS_\$_Foo"), pkgs))
    }

    private fun settings(app: HrModule) = HrSettings(
        rootDir = tmp.path, workDir = File(tmp, "w").path, dist = "/d", kotlinc = "/d/bin/kotlinc-native", compilerJar = "/d/c.jar",
        composePlugin = "/c.jar", klibList = "/k.txt", kaldoDylib = "/l.dylib", kaldoSha256 = null, target = "ios_simulator_arm64",
        orcRuntime = "/o.a", objcUtilsLib = "/u.a", llvmHome = null, useDaemon = false, socketDir = "/s", javaHome = "/j",
        modules = listOf(app), infoPlist = "/p", linkerOptions = emptyList(), keepCompactUnwind = true, port = 5567, udid = "booted",
        launchLog = "/l", readyMarker = null, launchTimeoutSec = 1, launchRetries = 1, reinstall = true, warmRestart = true,
    )

    @Test
    fun `the generated Info plist keeps its 0_2_2 bytes by default, UIDeviceFamily is configurable`() {
        // The text 0.2.2 generated (UIDeviceFamily [1]); the default must stay byte-identical (#9).
        val v022 = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0">
            |<dict>
            |    <key>CFBundleExecutable</key><string>KmpHr</string>
            |    <key>CFBundleIdentifier</key><string>org.example.kmphr.hotreload</string>
            |    <key>CFBundleName</key><string>KmpHr</string>
            |    <key>CFBundlePackageType</key><string>APPL</string>
            |    <key>CFBundleShortVersionString</key><string>1.0</string>
            |    <key>CFBundleVersion</key><string>1</string>
            |    <key>CADisableMinimumFrameDurationOnPhone</key><true/>
            |    <key>LSRequiresIPhoneOS</key><true/>
            |    <key>MinimumOSVersion</key><string>17.0</string>
            |    <key>UIDeviceFamily</key><array><integer>1</integer></array>
            |    <key>UILaunchScreen</key><dict/>
            |</dict>
            |</plist>
            |""".trimMargin()
        assertEquals(v022, Kmp.infoPlist("KmpHr", "org.example.kmphr.hotreload"))
        assertEquals(v022, Kmp.infoPlist("KmpHr", "org.example.kmphr.hotreload", listOf(1)))
        assertEquals(v022.replace("<integer>1</integer></array>", "<integer>1</integer><integer>2</integer></array>"),
            Kmp.infoPlist("KmpHr", "org.example.kmphr.hotreload", listOf(1, 2)))
        assertTrue("<key>UIDeviceFamily</key><array><integer>2</integer></array>" in Kmp.infoPlist("A", "a.b", listOf(2)))
        assertEquals(setOf(1, 2), Kmp.DEVICE_FAMILIES)
    }

    @Test
    fun `toolchain 7 (Compose rc01) - beta02 and rc01 projects pass, 1_13_0 and 1_14_0-alpha01 fail before packaging`() {
        // Case table for #12: each Compose module against the toolchain's copy of that module (compose-klibs carry the
        // fork's version, 1.13.0-rc01; androidx.compose.runtime comes from external-klibs/<version>/).
        val stdlib = klib("stdlib", "stdlib", abi = "2.5.0", dir = true)
        File(tmp, "tc/androidx.compose.runtime/runtime-iossimulatorarm64/1.13.0-rc01").mkdirs()
        val tcRuntime = klib("tc/androidx.compose.runtime/runtime-iossimulatorarm64/1.13.0-rc01/rt", "androidx.compose.runtime:runtime")
        val tcUi = klib("tc-ui", "org.jetbrains.compose.ui:ui", dir = true)
        val tc = listOf(tcRuntime.path, tcUi.path)
        fun deps(jb: String, androidx: String) = listOf(
            Kmp.Dependency("org.jetbrains.compose.ui:ui-iossimulatorarm64:$jb", klib("ui-$jb", "org.jetbrains.compose.ui:ui")),
            Kmp.Dependency("androidx.compose.runtime:runtime-iossimulatorarm64:$androidx", klib("rt-$androidx", "androidx.compose.runtime:runtime")),
        )
        val cases = listOf(
            Triple("1.13.0-beta02", "1.13.0-beta02", true),
            Triple("1.13.0-rc01", "1.13.0-rc01", true),
            Triple("1.13.0", "1.13.0-rc01", false),
            Triple("1.14.0-alpha01", "1.13.0-rc01", false),
            Triple("1.13.0-rc01", "1.13.0", false),          // the androidx side alone newer
        )
        for ((jb, ax, ok) in cases) {
            val r = runCatching { Kmp.selectKlibs(tc, stdlib, deps(jb, ax), "1.13.0-rc01", null) }
            assertEquals(ok, r.isSuccess, "ui $jb / runtime $ax: ${r.exceptionOrNull()?.message}")
            if (!ok) assertTrue("is newer than the toolchain's copy" in r.exceptionOrNull()!!.message!!, r.exceptionOrNull()!!.message)
        }
    }
}
