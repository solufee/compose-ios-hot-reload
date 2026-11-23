package com.solufee.cmp.hotreload

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider

/** #120: Kotlin/Native's included<digits> directories stay in the plugin's own temp directories and are removed. */
class IncludedTmpTest {
    @TempDir
    lateinit var tmp: File

    private fun settings() = HrSettings(
        rootDir = "/p", workDir = "/p/build/hotreload", dist = "/d", kotlinc = "/d/bin/kotlinc-native", compilerJar = "/d/c.jar",
        composePlugin = "/c.jar", klibList = "/k.txt", kaldoDylib = "/l/libKaldo.iossim.dylib", kaldoSha256 = "x", target = "ios_simulator_arm64",
        orcRuntime = "/o.a", objcUtilsLib = "/u.a", llvmHome = null, useDaemon = true, socketDir = "/s", javaHome = "/j",
        modules = listOf(HrModule(":app", ModuleKind.APP, "/p/app/src", emptyList())),
        infoPlist = "/p/app/Info.plist", linkerOptions = emptyList(), keepCompactUnwind = true, port = 5568, udid = "booted",
        launchLog = "/p/launch.log", readyMarker = null, launchTimeoutSec = 120, launchRetries = 3, reinstall = true, warmRestart = true,
    )

    @Test
    fun `each daemon gets a temp directory of its own next to its socket, and is told to clean it`() {
        val s = settings()
        assertEquals(File("/s/tmp-compile"), Daemons.tmpDir(s, "compile"))
        assertEquals(File("/s/tmp-klib"), Daemons.tmpDir(s, "klib"))
        val cmd = Daemons.command(s, s.compileSocket, "3G", File("/s/classes"), Daemons.tmpDir(s, "compile"))
        assertTrue("-Djava.io.tmpdir=/s/tmp-compile" in cmd, cmd.toString())
        assertTrue("-Dhr.cleanIncluded=true" in cmd, cmd.toString())
        // JVM options come before the main class
        assertTrue(cmd.indexOf("-Djava.io.tmpdir=/s/tmp-compile") < cmd.indexOf("HrCompileDaemon"), cmd.toString())
        assertEquals(listOf("HrCompileDaemon", "/d/c.jar", s.compileSocket), cmd.takeLast(3))
    }

    @Test
    fun `the kotlinc-native fallback runs with the given temp directory (JVM and the tools it starts)`() {
        val p = Pipeline(settings(), Logging.getLogger("t"))
        val env = p.compileEnvironment(cacheOnly = false, tmp = File("/w/tmp/direct-1"))
        assertEquals("/w/tmp/direct-1", env["TMPDIR"])
        assertTrue(env.getValue("JAVA_OPTS").endsWith("-Djava.io.tmpdir=/w/tmp/direct-1"), env.toString())
        // without a directory nothing changes (as before #120)
        assertFalse("TMPDIR" in p.compileEnvironment(cacheOnly = true), p.compileEnvironment(cacheOnly = true).toString())
        assertEquals("1", p.compileEnvironment(cacheOnly = true, tmp = File("/x"))["KONAN_HOT_RELOAD_CACHE_ONLY"])
    }

    @Test
    fun `the release check's compiler JVM gets its private temp directory explicitly (macOS ignores TMPDIR for java io tmpdir)`() {
        val env = ReleaseCheck.environment("/j", File("/w/release/tmp"))
        assertEquals("/w/release/tmp", env["TMPDIR"])
        assertEquals("-Djava.io.tmpdir=/w/release/tmp", env["JAVA_OPTS"])
        assertEquals("/j", env["JAVA_HOME"])
    }

    /** The daemon's own methods, compiled from the source the plugin ships (daemon/HrCompileDaemon.java). */
    private fun daemonClass(): Class<*> {
        val src = File(tmp, "src").apply { mkdirs() }
        val java = File(src, "HrCompileDaemon.java")
        java.writeBytes(Tools::class.java.getResourceAsStream("/com/solufee/cmp/hotreload/tools/HrCompileDaemon.java")!!.use { it.readBytes() })
        val out = File(tmp, "classes").apply { mkdirs() }
        val rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.path, java.path)
        assertEquals(0, rc, "javac HrCompileDaemon.java")
        return URLClassLoader(arrayOf(out.toURI().toURL()), null).loadClass("HrCompileDaemon")
    }

    @Test
    fun `after a request the daemon removes only the included directories that request created`() {
        val cls = daemonClass()
        val included = cls.getDeclaredMethod("included", Path::class.java).apply { isAccessible = true }
        val removeNew = cls.getDeclaredMethod("removeNewIncluded", Path::class.java, Set::class.java).apply { isAccessible = true }
        val dir = File(tmp, "daemon-tmp").apply { mkdirs() }
        File(dir, "included111/lib").mkdirs()                                   // there before the request
        File(dir, "included111/lib/libskiko.a").writeText("old")
        @Suppress("UNCHECKED_CAST")
        val before = included.invoke(null, dir.toPath()) as Set<String>
        assertEquals(setOf("included111"), before)
        // what the request unpacked
        File(dir, "included222/a/b").mkdirs(); File(dir, "included222/a/b/libskiko.a").writeText("new")
        File(dir, "included333").mkdirs()
        // not ours to remove: other names, and a plain file that starts with "included"
        File(dir, "konan_temp").mkdirs()
        File(dir, "included-notes.txt").writeText("x")
        val n = removeNew.invoke(null, dir.toPath(), before) as Int
        assertEquals(2, n)
        assertFalse(File(dir, "included222").exists())
        assertFalse(File(dir, "included333").exists())
        assertTrue(File(dir, "included111/lib/libskiko.a").isFile, "a directory that was there before the request stays")
        assertTrue(File(dir, "konan_temp").isDirectory)
        assertTrue(File(dir, "included-notes.txt").isFile)
        // a missing directory: nothing to do
        assertEquals(0, removeNew.invoke(null, File(tmp, "missing").toPath(), emptySet<String>()) as Int)
    }
}
