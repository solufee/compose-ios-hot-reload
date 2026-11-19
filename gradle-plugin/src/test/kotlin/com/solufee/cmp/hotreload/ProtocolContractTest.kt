package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Contract tests for the protocols the plugin implements itself (docs/INTERFACE.md §2.1, §4.1, §6).
 * When the low-level line changes a protocol it bumps interfaceVersion; [interface version is pinned]
 * then fails first and forces this file and Protocols.kt to be revisited.
 */
class ProtocolContractTest {
    @TempDir
    lateinit var tmp: File

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "docs/INTERFACE.md").isFile }

    @Test
    fun `interface version is pinned`() {
        val doc = File(repoRoot, "docs/INTERFACE.md").readText()
        val docVersion = Regex("""interfaceVersion (\d+)""").find(doc)!!.groupValues[1].toInt()
        assertEquals(8, Protocols.PROTOCOL_INTERFACE_VERSION, "protocol implementation was reviewed for interfaceVersion 8")
        assertEquals(Protocols.PROTOCOL_INTERFACE_VERSION, docVersion, "docs/INTERFACE.md changed version; re-check Protocols.kt")
        assertEquals(Protocols.PROTOCOL_INTERFACE_VERSION, PinnedToolchain.current.interfaceVersion)
    }

    @Test
    fun `reload request is byte-exact for two objects`() {
        val paths = listOf("/tmp/a/patch.o", "/tmp/b/ünïcode.o")
        val expected = ByteArrayOutputStream().apply {
            fun u32(v: Int) = write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            u32(2)
            for (p in paths) {
                val b = p.toByteArray(Charsets.UTF_8)
                u32(b.size)
                write(b)
            }
        }.toByteArray()

        // Minimal listener written from INTERFACE §4.1/§6: read u32 count, then u32 length + path per object.
        ServerSocket(0).use { server ->
            var received: List<String>? = null
            var raw: ByteArray? = null
            val t = thread {
                server.accept().use { c ->
                    val bytes = c.getInputStream().readAllBytes()
                    raw = bytes
                    val inp = DataInputStream(bytes.inputStream())
                    fun u32() = ByteBuffer.wrap(ByteArray(4).also { inp.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN).int
                    received = List(u32()) { String(ByteArray(u32()).also { inp.readFully(it) }, Charsets.UTF_8) }
                }
            }
            ReloadSender.send(server.localPort, paths)
            t.join(5000)
            assertArrayEquals(expected, raw)
            assertEquals(paths, received)
        }
    }

    @Test
    fun `daemon replies are parsed`() {
        assertEquals(CompileResult.Ok(123), CompileDaemonClient.parseReply("OK 123"))
        assertEquals(CompileResult.Err("COMPILATION_ERROR", 45), CompileDaemonClient.parseReply("ERR COMPILATION_ERROR 45"))
        assertEquals(CompileResult.Err("exception", 7), CompileDaemonClient.parseReply("ERR exception 7"))
        assertTrue(CompileDaemonClient.parseReply("HELLO") is CompileResult.Unreachable)
    }

    /** Talks to the real HrCompileDaemon; needs the toolchain (-Pcmp.hotreload.toolchainDir), skipped otherwise. */
    @Test
    fun `real daemon compiles OK, reports ERR on a syntax error, and stays alive`() {
        val dir = System.getProperty("cmp.hotreload.toolchainDir")
        assumeTrue(dir != null && File(dir, TOOLCHAIN_MANIFEST).isFile, "no toolchain configured; contract test against the real daemon skipped")
        val t = Toolchain.parse(File(dir, TOOLCHAIN_MANIFEST))
        val javaHome = System.getProperty("java.home")

        val classes = File(tmp, "classes")
        val src = File(repoRoot, "daemon/HrCompileDaemon.java")
        val javac = ProcessBuilder("$javaHome/bin/javac", "-d", classes.path, src.path).inheritIO().start()
        assertEquals(0, javac.waitFor())

        val sockDir = Files.createTempDirectory(File("/tmp").toPath(), "hrc").toFile()
        val sock = File(sockDir, "c.sock").path
        val log = File(tmp, "daemon.log")
        val daemon = ProcessBuilder(
            "$javaHome/bin/java", "-Xmx1500m", "-XX:+UseSerialGC", "-Dkonan.home=${t.kotlinDist}",
            "-cp", "${t.compilerJar}:${classes.path}", "HrCompileDaemon", t.compilerJar!!, sock,
        ).redirectErrorStream(true).redirectOutput(log).also { it.environment().remove("KONAN_HOT_RELOAD_CACHE_ONLY") }.start()
        try {
            val deadline = System.currentTimeMillis() + 60_000
            while (!(File(sock).exists() && log.readText().contains("READY")) && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue(File(sock).exists(), "daemon did not start: ${log.readText()}")

            fun lib(name: String, code: String): List<String> {
                val d = File(tmp, name).apply { mkdirs() }
                File(d, "$name.kt").writeText(code)
                return listOf("-target", t.target!!, "-p", "library", d.path, "-o", File(tmp, "out-$name").path)
            }
            val ok = CompileDaemonClient.request(sock, lib("good", "package contract\nfun answer(): Int = 42\n"))
            assertTrue(ok is CompileResult.Ok, "expected OK, got $ok; log:\n${log.readText().takeLast(3000)}")
            assertTrue(File(tmp, "out-good.klib").exists())

            val err = CompileDaemonClient.request(sock, lib("bad", "package contract\nfun broken( = \n"))
            assertTrue(err is CompileResult.Err, "expected ERR, got $err")

            val again = CompileDaemonClient.request(sock, lib("good2", "package contract\nfun again(): Int = 1\n"))
            assertTrue(again is CompileResult.Ok, "daemon must survive a failed compile; got $again")
        } finally {
            daemon.destroy()
            daemon.waitFor(10, TimeUnit.SECONDS)
            sockDir.deleteRecursively()
        }
    }
}
