package com.solufee.cmp.hotreload

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets

/**
 * The wire protocols of docs/INTERFACE.md, implemented in the plugin instead of spawning hrc.py / reload.py.
 * [PROTOCOL_INTERFACE_VERSION] is the interface version these implementations were written against; the
 * contract tests pin it, so a protocol change on the low-level side has to touch this file.
 */
object Protocols {
    // v3-v7 (manifest, packager duties, libKaldo location, link flags, bundle layout, compat classifier)
    // did not change these protocols. v8 added the daemon's `@klib` tool request, which the plugin uses only
    // through compat-classify.py --daemon; the compile request/reply and the reload protocol are unchanged.
    const val PROTOCOL_INTERFACE_VERSION = 8
}

sealed class CompileResult {
    data class Ok(val ms: Long) : CompileResult()
    data class Err(val code: String, val ms: Long) : CompileResult()
    data class Unreachable(val reason: String) : CompileResult()
}

/**
 * Client of HrCompileDaemon (INTERFACE §2.1): one argument per line, empty line ends; reply `OK <ms>` or
 * `ERR <code> <ms>`, where code is the compiler's exit code name or `exception`.
 */
object CompileDaemonClient {
    fun request(socket: String, args: List<String>): CompileResult {
        require(args.none { '\n' in it || it.isEmpty() }) { "compiler arguments must be non-empty single lines" }
        val ch = try {
            SocketChannel.open(StandardProtocolFamily.UNIX).also { it.connect(UnixDomainSocketAddress.of(File(socket).toPath())) }
        } catch (e: Exception) {
            return CompileResult.Unreachable(e.message ?: e.toString())
        }
        ch.use {
            val out = Channels.newOutputStream(ch)
            out.write((args.joinToString("\n") + "\n\n").toByteArray(StandardCharsets.UTF_8))
            out.flush()
            val line = Channels.newInputStream(ch).bufferedReader(StandardCharsets.UTF_8).readLine()?.trim()
                ?: return CompileResult.Unreachable("daemon closed the connection without a reply")
            return parseReply(line)
        }
    }

    fun parseReply(line: String): CompileResult {
        val p = line.split(' ')
        return when {
            p.size == 2 && p[0] == "OK" -> CompileResult.Ok(p[1].toLong())
            p.size == 3 && p[0] == "ERR" -> CompileResult.Err(p[1], p[2].toLong())
            else -> CompileResult.Unreachable("unexpected daemon reply: $line")
        }
    }
}

/** Sender of the runtime reload request (INTERFACE §4.1, §6): `u32 count` then per object `u32 length + path`, little endian. */
object ReloadSender {
    fun encode(paths: List<String>): ByteArray {
        val bytes = paths.map { it.toByteArray(StandardCharsets.UTF_8) }
        val buf = ByteBuffer.allocate(4 + bytes.sumOf { 4 + it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(bytes.size)
        for (b in bytes) {
            buf.putInt(b.size)
            buf.put(b)
        }
        return buf.array()
    }

    /** The interface sends exactly one (merged) object per reload; [paths] has more only in the contract test. */
    fun send(port: Int, paths: List<String>, timeoutMs: Int = 5000) {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            s.getOutputStream().apply {
                write(encode(paths))
                flush()
            }
        }
    }
}
