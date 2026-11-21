package com.solufee.cmp.hotreload

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * #62: follows a log file as it grows and hands out its new lines as they arrive. A small python3 helper waits on the
 * file with kqueue (NOTE_WRITE/NOTE_EXTEND) and writes every new chunk at once (unbuffered), so a ready line is seen
 * within milliseconds instead of at the next 0.2 s poll. (`tail -F` buffers its output into a pipe: a quiet app's last
 * lines could wait there for many seconds.) The helper is the plugin's own child and is stopped by [close].
 */
class LogFollower(log: File) : AutoCloseable {
    private val proc: Process = ProcessBuilder("python3", "-I", "-u", "-c", FOLLOW, log.path)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        .start()
    private val lines = LinkedBlockingQueue<String>()

    init {
        Thread({
            try {
                proc.inputStream.bufferedReader().forEachLine { lines.put(it) }
            } catch (_: java.io.IOException) {
                // closed
            }
        }, "cmp-hotreload-log-follower").apply { isDaemon = true; start() }
    }

    /** The lines that arrived, waiting up to [waitMs] for the first one; empty when none came. */
    fun next(waitMs: Long): List<String> {
        val first = lines.poll(waitMs, TimeUnit.MILLISECONDS) ?: return emptyList()
        val out = mutableListOf(first)
        lines.drainTo(out)
        return out
    }

    companion object {
        /** Prints the file from its start and then whatever is appended, waking on kqueue vnode events (1 s safety timeout). */
        private const val FOLLOW = """
import os, select, sys
fd = os.open(sys.argv[1], os.O_RDONLY)
kq = select.kqueue()
ev = select.kevent(fd, filter=select.KQ_FILTER_VNODE, flags=select.KQ_EV_ADD | select.KQ_EV_CLEAR,
                   fflags=select.KQ_NOTE_WRITE | select.KQ_NOTE_EXTEND)
out = sys.stdout.buffer
while True:
    data = os.read(fd, 65536)
    while data:
        out.write(data)
        out.flush()
        data = os.read(fd, 65536)
    kq.control([ev], 1, 1.0)
"""
    }

    override fun close() {
        proc.destroy()
        if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
    }
}
