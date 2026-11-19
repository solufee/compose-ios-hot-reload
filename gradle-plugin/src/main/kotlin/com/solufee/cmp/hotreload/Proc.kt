package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File

/** Small process helper: inherits the environment minus hot-reload switches, plus [env]. */
object Proc {
    /** Switches that must never leak from the caller's shell into a compile (e.g. CACHE_ONLY into a full build). */
    val SCRUB = listOf("KONAN_HOT_RELOAD_CACHE_ONLY", "KALDO_KEEP_COMPACT_UNWIND", "KALDO_HOT_PACKAGES", "KALDO_HOT_SYMBOLS_OUT")

    data class Result(val exit: Int, val output: String)

    fun run(
        cmd: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: File? = null,
        logger: Logger? = null,
        echo: Boolean = true,
    ): Result {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        cwd?.let { pb.directory(it) }
        val e = pb.environment()
        SCRUB.forEach { e.remove(it) }
        e.putAll(env)
        val p = pb.start()
        p.outputStream.close()
        val sb = StringBuilder()
        p.inputStream.bufferedReader().forEachLine { line ->
            sb.appendLine(line)
            if (echo) logger?.lifecycle(line)
        }
        return Result(p.waitFor(), sb.toString())
    }

    fun check(cmd: List<String>, env: Map<String, String> = emptyMap(), cwd: File? = null, logger: Logger? = null, echo: Boolean = true): String {
        val r = run(cmd, env, cwd, logger, echo)
        if (r.exit != 0) {
            throw GradleException(
                "CMP hot reload: `${cmd.joinToString(" ").take(300)}` failed (exit ${r.exit})" +
                    (if (echo) "" else ":\n" + r.output.takeLast(4000)),
            )
        }
        return r.output
    }
}
