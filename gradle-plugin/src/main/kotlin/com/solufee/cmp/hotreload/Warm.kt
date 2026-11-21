package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File

/**
 * Warm restart (INTERFACE §10.2 `warm`, §10.3 state contract): keep the app's serializable state, rebuild the
 * app and relaunch it with `--hr-state=PAGE,COUNT`.
 *
 * The state is whatever the app last wrote to `<Documents>/hr-state.json` (`{"page":N,"count":M}`). An app
 * that does not write the file still gets a warm restart, from its first page.
 */
class WarmRestart(private val s: HrSettings, private val logger: Logger) {
    data class State(val page: Int, val count: Int)

    /** Raw bytes of the app's Documents/hr-state.json, or null when the app wrote none. */
    fun savedStateFile(bundleId: String): ByteArray? {
        val r = Proc.run(listOf("xcrun", "simctl", "get_app_container", s.udid, bundleId, "data"), echo = false)
        if (r.exit != 0) return null
        val f = File(r.output.trim(), "Documents/$STATE_FILE")
        return if (f.isFile) f.readBytes() else null
    }

    fun savedState(bundleId: String): State? = savedStateFile(bundleId)?.let { parseState(it.toString(Charsets.UTF_8)) }

    /** Returns how long the warm restart took (ms), as printed in `WARM OK ms=`. */
    fun run(pipeline: Pipeline, tools: Tools, daemons: Daemons?, compat: Compat): Long {
        val t0 = System.currentTimeMillis()
        val info = pipeline.appInfo()
        // The whole file travels across the reinstall (any fields the app keeps, INTERFACE §10.3 plus the
        // app's own transient state); page/count, when present, are also passed as --hr-state.
        val stateFile = savedStateFile(info.bundleId)
        val state = stateFile?.let { parseState(it.toString(Charsets.UTF_8)) }
        logger.lifecycle(
            when {
                stateFile == null -> "warm: the app saved no state (Documents/$STATE_FILE); restarting from its first page " +
                    "(to come back where you were, write that file: README, \"State across a warm restart\")"
                state != null -> "warm: restoring page=${state.page} count=${state.count} (Documents/$STATE_FILE, ${stateFile.size} bytes)"
                else -> "warm: carrying the app's Documents/$STATE_FILE (${stateFile.size} bytes) into the new install"
            },
        )
        Proc.run(listOf("xcrun", "simctl", "terminate", s.udid, info.bundleId), echo = false)
        pipeline.packageApp(tools, daemons, beside = { compat.baseline(daemons) })
        val tPkg = System.currentTimeMillis()
        val launcher = Launcher(s, logger)
        val args = state?.let { listOf("--hr-state=${it.page},${it.count}") } ?: emptyList()
        val tokens = state?.let { listOf("page=${it.page}", "count=${it.count}") } ?: emptyList()
        launcher.launch(pipeline, args, tokens, stateFile?.let { mapOf(STATE_FILE to it) } ?: emptyMap())
        val end = System.currentTimeMillis()
        val log = File(s.launchLog).readText()
        if (log.lines().any { it.startsWith("EH FAIL") }) {
            throw GradleException("CMP hot reload: the restarted app reports EH FAIL (exceptions cannot unwind); see ${s.launchLog}")
        }
        logger.lifecycle(
            "WARM OK ms=${end - t0} ms_package=${tPkg - t0} ms_relaunch=${end - tPkg}" +
                (state?.let { " page=${it.page} count=${it.count}" } ?: " page=- count=-"),
        )
        return end - t0
    }

    companion object {
        const val STATE_FILE = "hr-state.json"
        private val PAGE = Regex(""""page"\s*:\s*(-?\d+)""")
        private val COUNT = Regex(""""count"\s*:\s*(-?\d+)""")

        /** `{"page":N,"count":M}`; a missing field counts as 0, an unreadable file as no state. */
        /**
         * page/count of `{"page":N,"count":M,...}` (a missing one counts as 0); null when the file is not a JSON
         * object or has neither field, i.e. the app keeps only its own state there.
         */
        fun parseState(json: String): State? {
            if (!json.trimStart().startsWith("{")) return null
            val page = PAGE.find(json)?.groupValues?.get(1)?.toIntOrNull()
            val count = COUNT.find(json)?.groupValues?.get(1)?.toIntOrNull()
            if (page == null && count == null) return null
            return State(page ?: 0, count ?: 0)
        }
    }
}
