package com.solufee.cmp.hotreload

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile

/**
 * Release check judges the __text digest (release-text-digest.py), not the raw __text bytes: identical binaries
 * agree, and changing one code byte must fail the check. Uses a real arm64 Mach-O linked by Xcode's clang
 * (with GOT loads of imported symbols); skipped where Xcode is not installed.
 */
class ReleaseDigestTest {
    @TempDir
    lateinit var tmp: File

    private fun settings() = HrSettings(
        rootDir = tmp.path, workDir = File(tmp, "w").path, dist = "/d", kotlinc = "/d/bin/kotlinc-native", compilerJar = "/d/c.jar",
        composePlugin = "/c.jar", klibList = "/k.txt", kaldoDylib = "/l.dylib", kaldoSha256 = null, target = "ios_simulator_arm64",
        orcRuntime = "/o.a", objcUtilsLib = "/u.a", llvmHome = null, useDaemon = false, socketDir = "/s", javaHome = "/j",
        modules = listOf(HrModule(":app", ModuleKind.APP, "/a", emptyList())), infoPlist = "/p", linkerOptions = emptyList(),
        keepCompactUnwind = true, port = 5567, udid = "booted", launchLog = "/l", readyMarker = null, launchTimeoutSec = 1,
        launchRetries = 1, reinstall = true, warmRestart = true,
    )

    /** File offset and size of __TEXT,__text (from otool -l). */
    private fun textSection(bin: File): Pair<Long, Long> {
        val lines = Proc.check(listOf("otool", "-l", bin.path), echo = false).lines().map { it.trim() }
        val i = lines.indexOfFirst { it == "sectname __text" }
        fun field(name: String) = lines.drop(i).first { it.startsWith("$name ") }.substringAfter(' ').trim()
        return field("offset").toLong() to java.lang.Long.decode(field("size"))
    }

    @Test
    fun `identical binaries have one digest, one changed code byte fails the release check`() {
        val clang = runCatching { Proc.check(listOf("xcrun", "--find", "clang"), echo = false).trim() }.getOrNull()
        val dyldInfo = runCatching { Proc.check(listOf("xcrun", "--find", "dyld_info"), echo = false).trim() }.getOrNull()
        assumeTrue(!clang.isNullOrEmpty() && !dyldInfo.isNullOrEmpty(), "no Xcode clang/dyld_info; digest test skipped")
        val src = File(tmp, "m.c").apply {
            writeText("#include <stdio.h>\n#include <stdlib.h>\nint main(int c, char **v) { puts(v[0]); printf(\"%d\\n\", abs(c - 3)); return c > 2; }\n")
        }
        val bin = File(tmp, "m")
        Proc.check(listOf("xcrun", "clang", "-arch", "arm64", "-O1", src.path, "-o", bin.path), echo = false)
        val copy = File(tmp, "m-copy").also { bin.copyTo(it) }
        val check = ReleaseCheck(settings(), Logging.getLogger("t"))

        val base = check.fingerprint(bin)
        val same = check.fingerprint(copy)
        assertTrue(Regex("[0-9a-f]{64}").matches(base.textDigest!!), base.textDigest)
        assertEquals(emptyList<String>(), ReleaseCheck.diffs(base, same))

        // Counter-example: flip one byte of the first instruction.
        val (off, size) = textSection(copy)
        assertTrue(size > 4)
        RandomAccessFile(copy, "rw").use { f -> f.seek(off); val b = f.read(); f.seek(off); f.write(b xor 0x01) }
        val changed = check.fingerprint(copy)
        assertNotEquals(base.textDigest, changed.textDigest)
        val d = ReleaseCheck.diffs(base, changed)
        assertTrue(d.any { it.startsWith("__TEXT,__text digest") }, d.toString())

        // A raw __text difference alone is recorded, not judged.
        assertEquals(emptyList<String>(), ReleaseCheck.diffs(base, base.copy(textSha = "0".repeat(64))))
    }
}
