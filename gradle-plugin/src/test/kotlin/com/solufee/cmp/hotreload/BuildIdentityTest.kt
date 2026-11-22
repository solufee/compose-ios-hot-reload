package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** #98: the build identity and what it clears. */
class BuildIdentityTest {
    @TempDir
    lateinit var tmp: File

    private fun settings(modules: List<HrModule> = emptyList(), stableKeys: Boolean = true, kgp: String? = "2.4.21", keepCompactUnwind: Boolean = true): HrSettings {
        val dist = File(tmp, "toolchain/kotlin-native").apply { mkdirs() }
        File(dist, "toolchain.json").writeText(
            """{"interfaceVersion": 8, "toolchainVersion": "2.5.255-hotreload.11.1", "kotlin": {"version": "2.5.255-SNAPSHOT", "patchSha256": "33105748"},""" +
                """ "compose": {"version": "1.13.0-rc01", "patchSha256": "8f9229c0"}}""",
        )
        fun file(name: String, text: String) = File(tmp, "toolchain/$name").apply { if (!isFile) writeText(text) }.path
        return HrSettings(
            rootDir = tmp.path, workDir = File(tmp, "build/hotreload").path, dist = dist.path, kotlinc = "/k",
            compilerJar = file("compiler.jar", "compiler-11.1"), composePlugin = file("compose.jar", "compose"), klibList = file("klibs.txt", "a\nb\n"),
            kaldoDylib = file("libkaldo.dylib", "kaldo-1"), kaldoSha256 = null, target = "ios_simulator_arm64", orcRuntime = "/o.a", objcUtilsLib = "/u.a",
            llvmHome = File(tmp, "toolchain/llvm").apply { File(this, "bin").mkdirs(); listOf("llvm-objcopy", "llvm-nm").forEach { t -> File(this, "bin/$t").apply { if (!isFile) writeText(t) } } }.path, useDaemon = false, socketDir = "/s", javaHome = "/j", modules = modules, infoPlist = "/p", linkerOptions = emptyList(),
            keepCompactUnwind = keepCompactUnwind, port = 5567, udid = "booted", launchLog = "/l", readyMarker = null, launchTimeoutSec = 1, launchRetries = 1,
            reinstall = true, warmRestart = true, stableKeys = stableKeys, projectKotlinVersion = kgp,
        )
    }

    private fun line(lines: List<String>, key: String) = lines.single { it.startsWith("$key ") }

    @Test
    fun `the identity holds the plugin version, the toolchain by content, the compiler plugins and their options`() {
        val plugin = File(tmp, "plugins/acme-compiler-plugin-1.0.jar").apply { parentFile.mkdirs(); writeText("v1") }
        val app = HrModule(":app", ModuleKind.APP, tmp.path, emptyList(), compilerPlugins = listOf(plugin.path), pluginOptions = listOf("plugin:acme:k=v"))
        val s = settings(listOf(app))
        val a = BuildIdentity.lines(s, pluginVersion = "0.2.8")
        assertEquals("plugin 0.2.8", line(a, "plugin"))
        assertEquals("toolchainVersion 2.5.255-hotreload.11.1", line(a, "toolchainVersion"))
        assertEquals("kotlin 2.5.255-SNAPSHOT 33105748", line(a, "kotlin"))
        assertTrue(line(a, "compilerPlugins").contains("acme-compiler-plugin-1.0.jar="), line(a, "compilerPlugins"))
        assertEquals("pluginOptions :app=plugin:acme:k=v", line(a, "pluginOptions"))
        assertEquals("abi stableKeys=true", line(a, "abi"))

        // the same compiler jar path with other contents (a toolchain unpacked again into the same directory)
        File(s.compilerJar).apply { writeText("compiler-11.2"); setLastModified(lastModified() + 2000) }
        val b = BuildIdentity.lines(s, pluginVersion = "0.2.8")
        assertTrue(line(a, "compilerJar") != line(b, "compilerJar"))
        assertTrue(BuildIdentity.lines(s, pluginVersion = "0.2.9") != b)
        plugin.apply { writeText("v2"); setLastModified(lastModified() + 2000) }
        assertTrue(line(BuildIdentity.lines(s, "0.2.8"), "compilerPlugins") != line(b, "compilerPlugins"))
        assertTrue(line(BuildIdentity.lines(settings(listOf(app), stableKeys = false), "0.2.8"), "abi") != line(b, "abi"))
        // Kaldo and the LLVM tools of the merge step, by content
        File(s.kaldoDylib).apply { writeText("kaldo-2"); setLastModified(lastModified() + 2000) }
        assertTrue(line(BuildIdentity.lines(s, "0.2.8"), "kaldo") != line(b, "kaldo"))
        File(s.llvmHome!!, "bin/llvm-objcopy").apply { writeText("objcopy-2"); setLastModified(lastModified() + 2000) }
        assertTrue(line(BuildIdentity.lines(s, "0.2.8"), "llvm") != line(b, "llvm"))
    }

    @Test
    fun `only the project's Kotlin Gradle plugin version changes the identity`() {
        val a = BuildIdentity.lines(settings(kgp = "2.4.21"), "0.2.9")
        val b = BuildIdentity.lines(settings(kgp = "2.5.0"), "0.2.9")
        assertEquals("projectKotlin 2.4.21", line(a, "projectKotlin"))
        assertEquals("projectKotlin 2.5.0", line(b, "projectKotlin"))
        assertEquals(listOf("projectKotlin 2.5.0"), b - a.toSet(), "only the projectKotlin line differs")
        assertTrue(BuildIdentity.digest(a) != BuildIdentity.digest(b))
        // the change is reported and clears the state
        val s = settings(kgp = "2.4.21")
        BuildIdentity.ensure(s, null, a)
        File(s.workDir, "klib/core/core.klib").apply { parentFile.mkdirs(); writeText("x") }
        val reason = BuildIdentity.ensure(s, null, b)
        assertEquals("projectKotlin 2.4.21 -> 2.5.0", reason)
        // a packaging switch that changes the merge outputs is in the identity too
        assertTrue(line(BuildIdentity.lines(settings(keepCompactUnwind = false), "0.2.9"), "packaging") != line(a, "packaging"))
    }

    @Test
    fun `a change clears the compiled state and stops the daemons, keeps tools, the release baseline and generated files`() {
        val s = settings()
        val work = File(s.workDir)
        // a fresh build directory: nothing to clear, the identity is recorded
        assertNull(BuildIdentity.ensure(s, null, BuildIdentity.lines(s, "0.2.8")))
        assertTrue(File(work, BuildIdentity.FILE).isFile)
        val compiled = listOf("klib/core/core.klib", "ic/x/cache.a", "auto-cache/y.a", "merge-cache/z.a", "compat/app.klib", "app/hot-symbols.txt", "KmpHr.app/KmpHr")
        val kept = listOf("tools/abc/merge-payloads.py", "release/baseline.fingerprint", "generated/Info.plist")
        (compiled + kept).forEach { File(work, it).apply { parentFile.mkdirs(); writeText("x") } }
        var stopped = 0
        // nothing changed: nothing cleared
        assertNull(BuildIdentity.ensure(s, null, BuildIdentity.lines(s, "0.2.8")) { stopped++ })
        assertTrue(compiled.all { File(work, it).isFile } && stopped == 0)
        // plugin upgrade: everything compiled goes, the daemons are stopped
        val reason = BuildIdentity.ensure(s, null, BuildIdentity.lines(s, "0.2.9")) { stopped++ }
        assertTrue(reason != null && reason.contains("plugin 0.2.8 -> 0.2.9"), reason)
        assertTrue(compiled.none { File(work, it).exists() }, work.list()?.toList().toString())
        assertTrue(kept.all { File(work, it).isFile }, "tools, release baseline and generated files are kept")
        assertEquals(1, stopped)
        assertEquals(BuildIdentity.lines(s, "0.2.9"), File(work, BuildIdentity.FILE).readLines())
    }

    @Test
    fun `a hot reload alone reports a change but clears and records nothing (the running app keeps its package)`() {
        val s = settings()
        BuildIdentity.ensure(s, null, BuildIdentity.lines(s, "0.2.8"))
        val app = File(s.workDir, "app/hot-symbols.txt").apply { parentFile.mkdirs(); writeText("x") }
        val recorded = File(s.workDir, BuildIdentity.FILE).readText()
        val reason = BuildIdentity.report(s, null, BuildIdentity.lines(settings(stableKeys = false), "0.2.8"))
        assertEquals("abi stableKeys=true -> stableKeys=false", reason)
        assertTrue(app.isFile, "the packaged app is kept")
        assertEquals(recorded, File(s.workDir, BuildIdentity.FILE).readText(), "the identity of the packaged app stays recorded")
        // back to the packaged setting: no change
        assertNull(BuildIdentity.changes(s, BuildIdentity.lines(s, "0.2.8")))
    }

    @Test
    fun `a fresh build directory with only checks and caches is not reported as old state`() {
        val s = settings()
        File(s.workDir, "toolchain-check.cache").apply { parentFile.mkdirs(); writeText("x") }
        assertNull(BuildIdentity.ensure(s, null, BuildIdentity.lines(s, "0.2.9")))
        assertTrue(File(s.workDir, "toolchain-check.cache").isFile)
    }

    @Test
    fun `compiled state without a recorded identity (an older plugin) is cleared`() {
        val s = settings()
        val klib = File(s.workDir, "klib/core/core.klib").apply { parentFile.mkdirs(); writeText("old") }
        val reason = BuildIdentity.ensure(s, null, BuildIdentity.lines(s, "0.2.9"))
        assertTrue(reason != null && reason.contains("no build identity recorded"), reason)
        assertTrue(!klib.exists())
    }

    @Test
    fun `a release baseline from another toolchain is named, hot reload only switches are not`() {
        val a = BuildIdentity.lines(settings(), "0.2.9")
        assertEquals(emptyList<String>(), ReleaseCheck.identityChanges(a, a))
        assertEquals(emptyList<String>(), ReleaseCheck.identityChanges(a, BuildIdentity.lines(settings(stableKeys = false, keepCompactUnwind = false), "0.2.9")))
        assertEquals(listOf("projectKotlin"), ReleaseCheck.identityChanges(a, BuildIdentity.lines(settings(kgp = "2.5.0"), "0.2.9")))
        val s = settings()
        File(s.compilerJar).apply { writeText("compiler-11.2"); setLastModified(lastModified() + 2000) }
        assertEquals(listOf("plugin", "compilerJar"), ReleaseCheck.identityChanges(a, BuildIdentity.lines(s, "0.2.10")))
    }

    @Test
    fun `file hashes are reused while size and mtime are unchanged`() {
        val f = File(tmp, "big.jar").apply { writeText("aaaa") }
        val cacheFile = File(tmp, "sha.cache")
        val first = BuildIdentity.ShaCache(cacheFile).also { it.sha(f); it.save() }
        assertEquals(Tools.sha256("aaaa".toByteArray()), BuildIdentity.ShaCache(cacheFile).sha(f))
        // same size and mtime: the recorded sha is reused (no read of the file)
        val mtime = f.lastModified()
        f.writeText("bbbb")
        f.setLastModified(mtime)
        assertEquals(Tools.sha256("aaaa".toByteArray()), BuildIdentity.ShaCache(cacheFile).sha(f))
        // a new mtime: read again
        f.setLastModified(mtime + 2000)
        assertEquals(Tools.sha256("bbbb".toByteArray()), BuildIdentity.ShaCache(cacheFile).sha(f))
        assertTrue(first.sha(File(tmp, "nope.jar")) == "missing")
    }
}
