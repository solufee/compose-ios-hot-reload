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
                    (if (echo) "" else ":\n" + failureText(r.output)),
            )
        }
        return r.output
    }

    private val KEY_LINE = Regex("""^\s*(ld: (?!warning)|duplicate symbol|Undefined symbols|error: |e: )""")

    /**
     * The tail of a failed tool's output, preceded by its diagnostic lines (linker and compiler errors, each with the
     * two lines after it) when the tail does not hold them: a tool that prints a long traceback after the linker's
     * message would otherwise push "duplicate symbol ..." out of the error.
     */
    fun failureText(output: String, tail: Int = 4000): String {
        val end = output.takeLast(tail)
        val lines = output.lines()
        val key = lines.indices.filter { KEY_LINE.containsMatchIn(lines[it]) }
            .flatMap { i -> (i..minOf(i + 2, lines.lastIndex)).toList() }.distinct()
            .map { lines[it] }.take(30)
        if (key.isEmpty() || key.all { it in end }) return end
        return key.joinToString("\n") + "\n...\n" + end
    }
}
