package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Lifecycle of this project's two compile daemons (INTERFACE §2.1): start, reuse, health check, stop.
 * Sockets live in a private directory (0700, sockets 0600). Only this project's daemons are ever stopped.
 */
class Daemons(private val s: HrSettings, private val logger: Logger) {
    private val sockets = listOf(s.compileSocket, s.klibSocket)
    private val stateFile = File(s.socketDir, "daemons.state")
    private fun log(kind: String) = File(s.workDir, "daemon-$kind.log")

    fun ensure(tools: Tools) {
        val expected = state(tools)
        if (sockets.all(::alive) && stateFile.isFile && stateFile.readText() == expected) {
            checkPrivate()
            logger.lifecycle("CMP hot reload: reusing compile daemons ${pids()}")
            return
        }
        if (sockets.any(::alive)) logger.lifecycle("CMP hot reload: restarting compile daemons (toolchain, tools or health changed)")
        stop()
        ensurePrivateDir(File(s.socketDir))
        // #90: the daemon's classes are compiled by this JDK's javac; another JDK gets its own copy
        val classes = File(s.socketDir, "classes-" + (tools.shas["HrCompileDaemon.java"] ?: "x").take(12) + "-" + Tools.sha256(s.javaHome.toByteArray()).take(8))
        if (!File(classes, "HrCompileDaemon.class").isFile) {
            classes.mkdirs()
            Proc.check(listOf("${s.javaHome}/bin/javac", "-d", classes.path, tools.daemonSource.path), logger = logger)
        }
        File(s.workDir).mkdirs()
        start(s.compileSocket, log("compile"), cacheOnly = true, xmx = "3G", classes = classes, tmp = tmpDir(s, "compile"))
        start(s.klibSocket, log("klib"), cacheOnly = false, xmx = "1500m", classes = classes, tmp = tmpDir(s, "klib"))
        stateFile.writeText(expected)
        Files.setPosixFilePermissions(stateFile.toPath(), PosixFilePermissions.fromString("rw-------"))
        checkPrivate()
        logger.lifecycle("CMP hot reload: compile daemons ready ${pids()}")
    }

    private fun state(tools: Tools) = stateOf(s, tools.shas["HrCompileDaemon.java"])

    private fun start(sock: String, log: File, cacheOnly: Boolean, xmx: String, classes: File, tmp: File) {
        // #120: a temp directory of its own; the daemon removes what each request unpacked there (included<digits>)
        tmp.deleteRecursively()
        ensurePrivateDir(tmp)
        val pb = ProcessBuilder(command(s, sock, xmx, classes, tmp))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(log))
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        pb.environment().remove("KONAN_HOT_RELOAD_CACHE_ONLY")
        if (cacheOnly) pb.environment()["KONAN_HOT_RELOAD_CACHE_ONLY"] = "1"
        pb.environment()["TMPDIR"] = tmp.path
        val p = pb.start()
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (File(sock).exists() && log.readText().contains("READY")) {
                Files.setPosixFilePermissions(File(sock).toPath(), PosixFilePermissions.fromString("rw-------"))
                return
            }
            if (!p.isAlive) break
            Thread.sleep(100)
        }
        p.destroy()
        throw GradleException("CMP hot reload: compile daemon did not start on $sock; log ${log.path}:\n${log.readText().takeLast(2000)}")
    }

    fun pids(): List<Long> {
        val out = Proc.run(listOf("ps", "-axo", "pid=,command="), echo = false).output
        return out.lines().mapNotNull { line ->
            val t = line.trim()
            val sp = t.indexOf(' ')
            if (sp <= 0) return@mapNotNull null
            val cmd = t.substring(sp + 1)
            if ("HrCompileDaemon" in cmd && sockets.any { cmd.endsWith(" $it") }) t.substring(0, sp).toLongOrNull() else null
        }
    }

    fun stop() {
        val pids = pids()
        pids.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroy() } }
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && pids.any { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }) Thread.sleep(100)
        pids.forEach { pid -> ProcessHandle.of(pid).ifPresent { if (it.isAlive) it.destroyForcibly() } }
        sockets.forEach { File(it).delete() }
        stateFile.delete()
        if (pids.isNotEmpty()) logger.lifecycle("CMP hot reload: stopped compile daemons $pids")
        // #120: the daemons' own temp directories go with them (only these two directories, nothing else in TMPDIR)
        val freed = KINDS.map { tmpDir(s, it) }.filter { it.isDirectory }.sumOf { d -> d.walkBottomUp().filter { it.isFile }.sumOf { it.length() }.also { d.deleteRecursively() } }
        if (freed > 1024 * 1024) logger.lifecycle("CMP hot reload: removed the daemons' temp directories (${freed / (1024 * 1024)} MB)")
    }

    /** Bytes the daemon has logged so far; pass to [logSince] after a failed request. */
    fun logSize(kind: String): Long = log(kind).length()
    fun logSince(kind: String, offset: Long): String =
        log(kind).takeIf { it.isFile }?.readBytes()?.let { String(it, minOf(offset.toInt(), it.size), it.size - minOf(offset.toInt(), it.size)) } ?: ""

    private fun checkPrivate() {
        val me = System.getProperty("user.name")
        val dir = File(s.socketDir).toPath()
        val dirPerms = Files.getPosixFilePermissions(dir)
        if (dirPerms != setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)) {
            throw GradleException("CMP hot reload: socket directory $dir must be 0700, is ${PosixFilePermissions.toString(dirPerms)}")
        }
        for (sock in sockets) {
            val p = File(sock).toPath()
            val owner = Files.getOwner(p).name
            if (owner != me) throw GradleException("CMP hot reload: $p is owned by $owner, not $me; refusing to use it")
            val perms = Files.getPosixFilePermissions(p)
            if (perms.any { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") }) {
                throw GradleException("CMP hot reload: $p must be 0600, is ${PosixFilePermissions.toString(perms)}")
            }
        }
    }

    companion object {
        val KINDS = listOf("compile", "klib")

        /** #120: a daemon's private temp directory, next to its socket ([HrSettings.socketDir], 0700). */
        fun tmpDir(s: HrSettings, kind: String): File = File(s.socketDir, "tmp-$kind")

        /**
         * The daemon's command line. #120: java.io.tmpdir is the daemon's own directory (Kotlin/Native unpacks the static
         * libraries of a link there, `included<digits>`, and deletes them only at JVM exit), and `-Dhr.cleanIncluded=true`
         * makes the daemon remove the ones each request created (daemon/HrCompileDaemon.java).
         */
        fun command(s: HrSettings, sock: String, xmx: String, classes: File, tmp: File): List<String> = listOf(
            "${s.javaHome}/bin/java", "-ea", "-Xmx$xmx", "-XX:+UseSerialGC",
            "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=20", "-Dkonan.home=${s.dist}",
            "-Djava.io.tmpdir=${tmp.path}", "-Dhr.cleanIncluded=true",
            "-cp", "${s.compilerJar}:${classes.path}", "HrCompileDaemon", s.compilerJar, sock,
        )

        /**
         * What the running daemons were started with; any difference restarts them. #90: the JDK is part of it (a
         * daemon on the old JDK would keep failing on a plugin that needs the new one after the user switched JDK).
         */
        fun stateOf(s: HrSettings, daemonSourceSha: String?): String =
            "jar=${s.compilerJar}\ndist=${s.dist}\nsource=$daemonSourceSha\njava=${s.javaHome}\n"

        /** Health check: the daemon answers when its socket accepts a connection. */
        fun alive(socket: String): Boolean {
            val f = File(socket)
            if (!f.exists()) return false
            return try {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(f.toPath())) }
                true
            } catch (e: Exception) {
                false
            }
        }

        fun ensurePrivateDir(dir: File) {
            val perms = PosixFilePermissions.fromString("rwx------")
            val chain = generateSequence(dir) { it.parentFile }.takeWhile { !it.exists() }.toList()
            for (f in chain.asReversed()) {
                f.mkdir()
                Files.setPosixFilePermissions(f.toPath(), perms)
            }
            Files.setPosixFilePermissions(dir.toPath(), perms)
        }
    }
}
