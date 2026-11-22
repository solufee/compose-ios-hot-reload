package com.solufee.cmp.hotreload

import org.gradle.api.logging.Logger
import java.io.File

/**
 * #107: libKaldo writes `/tmp/perf-<pid>.map` (about 70 MB per start, appended, never removed) when the app runs with
 * KALDO_PERF_MAP=1, for symbolizing JIT frames in a profiler. The plugin sets it only with `perfMap` on, records the pid
 * of every app it starts that way (`perf-map-pids.txt` in the hot reload directory) and deletes those maps once their
 * process is gone: before each launch (hotReloadRun, warm restart) and in hotReloadClean. Only recorded pids are touched.
 */
object PerfMaps {
    const val RECORD = "perf-map-pids.txt"

    fun record(work: File) = File(work, RECORD)

    fun mapOf(pid: Long, dir: File = File("/tmp")) = File(dir, "perf-$pid.map")

    /** Records [pid] as an app started with KALDO_PERF_MAP=1. */
    fun remember(work: File, pid: Long) {
        val f = record(work)
        val pids = pids(work)
        if (pid in pids) return
        f.parentFile.mkdirs()
        f.writeText((pids + pid).joinToString("\n", postfix = "\n"))
    }

    fun pids(work: File): List<Long> = record(work).takeIf { it.isFile }?.readLines()?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

    /**
     * Deletes the perf maps of recorded pids whose process has ended and forgets them; a pid still running keeps its map
     * and stays recorded. Returns the number of files deleted. [alive] and [dir] are for tests.
     */
    fun clean(work: File, logger: Logger?, dir: File = File("/tmp"), alive: (Long) -> Boolean = { ProcessHandle.of(it).isPresent }): Int {
        val pids = pids(work)
        if (pids.isEmpty()) return 0
        var deleted = 0
        var bytes = 0L
        val keep = pids.filter { pid ->
            if (alive(pid)) return@filter true
            val m = mapOf(pid, dir)
            if (m.isFile) {
                bytes += m.length()
                if (m.delete()) deleted++
            }
            false
        }
        if (keep.isEmpty()) record(work).delete() else record(work).writeText(keep.joinToString("\n", postfix = "\n"))
        if (deleted > 0) logger?.lifecycle("CMP hot reload: deleted $deleted perf map(s) of earlier app processes (${bytes / (1024 * 1024)} MB in ${dir.path})")
        return deleted
    }
}
