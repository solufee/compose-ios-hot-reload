package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File

/**
 * Installs and launches the packaged app with the runtime switches of INTERFACE §7, and returns once the
 * app is up. The `simctl launch --console-pty` process keeps running and streams the console to the launch log.
 */
class Launcher(private val s: HrSettings, private val logger: Logger) {
    data class Started(val attempt: Int, val pid: Long?, val seconds: Double)

    /** Runtime environment, prefixed with SIMCTL_CHILD_ so simctl passes it to the app. */
    /** INTERFACE §3.3 #2 (SYNTH_EH_FRAME) and #3 (narrowed KALDO_HOT_SYMBOLS) are set here. */
    fun appEnvironment(): Map<String, String> = mapOf(
        "KONAN_ORC_RUNTIME_PATH" to s.orcRuntime,
        "KALDO_SYNTH_EH_FRAME" to "1",
        "KALDO_PERF_MAP" to "1",
        "KALDO_HOT_SYMBOLS" to s.hotSymbols.path,
        "KONAN_HOT_RELOAD_PORT" to s.port.toString(),
    ).mapKeys { "SIMCTL_CHILD_${it.key}" }

    /**
     * [appArgs] go to the app's argv (e.g. `--hr-state=2,3` for a warm restart). With [readyTokens], the app
     * counts as started only when one console line contains the ready marker and every token (e.g. the
     * restored `page=2` and `count=3`).
     */
    fun launch(pipeline: Pipeline, appArgs: List<String> = emptyList(), readyTokens: List<String> = emptyList()): Started {
        val info = pipeline.appInfo()
        val bundle = pipeline.appBundle(info)
        if (!bundle.isDirectory) throw GradleException("CMP hot reload: ${bundle.path} not found; run ./gradlew hotReloadPackage first")
        if (!s.hotSymbols.isFile) throw GradleException("CMP hot reload: ${s.hotSymbols.path} missing (INTERFACE §3.3 #3); run ./gradlew hotReloadPackage")
        val udid = s.udid
        if (udid == "booted") requireSingleBootedSimulator()
        Proc.run(listOf("xcrun", "simctl", "terminate", udid, info.bundleId), echo = false)
        requireFreePort()
        if (s.reinstall) {
            Proc.run(listOf("xcrun", "simctl", "uninstall", udid, info.bundleId), echo = false)
            Proc.check(listOf("xcrun", "simctl", "install", udid, bundle.path), logger = logger)
        }
        val log = File(s.launchLog)
        for (attempt in 1..s.launchRetries) {
            log.parentFile?.mkdirs()
            log.writeText("")
            val t0 = System.nanoTime()
            // Same shape as the reference run.sh, which proved reliable: simctl writes into a pipe (tee), not
            // straight into a file; and the pipeline runs in its own session so a retry can kill all of it.
            val pb = ProcessBuilder(
                "python3", "-I", "-c", SETSID_EXEC, "/bin/bash", "-c",
                "log=\"\$1\"; shift; xcrun simctl launch --console-pty \"\$@\" 2>&1 | tee -a \"\$log\" > /dev/null",
                "launch", log.path, udid, info.bundleId, *appArgs.toTypedArray(),
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            pb.environment().putAll(appEnvironment())
            val proc = pb.start()
            val deadline = System.currentTimeMillis() + s.launchTimeoutSec * 1000L
            var upSince = 0L
            while (System.currentTimeMillis() < deadline) {
                val text = log.readText()
                if (text.contains("HR server: Start() failed")) {
                    Proc.run(listOf("xcrun", "simctl", "terminate", udid, info.bundleId), echo = false)
                    throw GradleException("CMP hot reload: the app could not open its reload port ${s.port} (\"HR server: Start() failed\" in ${log.path}); " +
                        "another app or process is using it. " + portHint())
                }
                val pid = appPid(info.executable)
                val ready = if (readyTokens.isNotEmpty()) {
                    text.replace('\r', '\n').lines().any { l -> (s.readyMarker == null || l.contains(s.readyMarker)) && l.trim().split(Regex("\\s+")).containsAll(readyTokens) }
                } else if (s.readyMarker != null) text.contains(s.readyMarker) else {
                    // Without a marker: Kotlin main has started and the app stayed alive for 3 s.
                    if (text.contains("calling Konan_start") && pid != null) {
                        if (upSince == 0L) upSince = System.currentTimeMillis()
                        System.currentTimeMillis() - upSince > 3000
                    } else false
                }
                if (ready && pid != null) {
                    val sec = (System.nanoTime() - t0) / 1e9
                    logger.lifecycle("CMP hot reload: app started (attempt $attempt, pid $pid, %.1f s, port ${s.port}, log ${log.path})".format(sec))
                    return Started(attempt, pid, sec)
                }
                if (!proc.isAlive) break
                Thread.sleep(200)
            }
            Proc.run(listOf("kill", "-TERM", "-${proc.pid()}"), echo = false)
            proc.destroy()
            Proc.run(listOf("xcrun", "simctl", "terminate", udid, info.bundleId), echo = false)
            logger.warn("CMP hot reload: attempt $attempt did not reach ${s.readyMarker ?: "a running Kotlin main"}" +
                (if (readyTokens.isNotEmpty()) " with ${readyTokens.joinToString(" ")}" else "") + "; " +
                "last log lines:\n" + log.readText().replace('\r', '\n').lines().filter { it.isNotBlank() }.takeLast(5).joinToString("\n"))
        }
        throw GradleException("CMP hot reload: app did not start after ${s.launchRetries} attempts; see ${log.path}")
    }

    companion object {
        /** "iPhone 17 Pro (UDID) (Booted)" lines of `simctl list devices booted`. */
        fun bootedDevices(listOutput: String): List<String> =
            listOutput.lines().map { it.trim() }.filter { it.endsWith("(Booted)") }

        /**
         * PID from `ps -axo pid=,command=` output: the app binary inside this device's container, with or
         * without arguments (a warm restart passes `--hr-state=...`).
         */
        fun findAppPid(psOutput: String, udid: String, executable: String): Long? {
            val devicePart = if (udid == "booted") "" else "/Devices/$udid/"
            val binary = "/$executable.app/$executable"
            return psOutput.lines().map { it.trim() }.firstOrNull { l ->
                l.contains(devicePart) && (l.endsWith(binary) || l.contains("$binary "))
            }?.substringBefore(' ')?.toLongOrNull()
        }

        /** Starts argv[1:] as the leader of a new session (own process group). */
        private const val SETSID_EXEC = "import os,sys; os.setsid(); os.execvp(sys.argv[1], sys.argv[1:])"
    }

    /**
     * The simulator shares the host's network: an app of another project (or a stale one) that still listens on the
     * reload port makes this app's reload server fail, and every hot reload then times out.
     */
    private fun requireFreePort() {
        repeat(10) {
            val holder = portHolder() ?: return
            if (it == 9) throw GradleException("CMP hot reload: port ${s.port} is already in use by $holder. " + portHint())
            Thread.sleep(300)   // our own app may still be shutting down
        }
    }

    private fun portHolder(): String? {
        val out = Proc.run(listOf("lsof", "-nP", "-iTCP:${s.port}", "-sTCP:LISTEN", "-Fpc"), echo = false).output
        val pid = out.lines().firstOrNull { it.startsWith("p") }?.drop(1) ?: return null
        val cmd = out.lines().firstOrNull { it.startsWith("c") }?.drop(1) ?: "?"
        return "$cmd (pid $pid)"
    }

    private fun portHint() =
        "Stop that app (xcrun simctl terminate <UDID> <its bundle id>) or give this project another port: cmp.hotreload.port=<port> in local.properties."

    /** "booted" is ambiguous unless exactly one simulator is booted; say which ones and how to choose. */
    private fun requireSingleBootedSimulator() {
        val out = Proc.run(listOf("xcrun", "simctl", "list", "devices", "booted"), echo = false).output
        val booted = bootedDevices(out)
        if (booted.size == 1) return
        throw GradleException(
            if (booted.isEmpty()) "CMP hot reload: no simulator is booted. Boot one (xcrun simctl boot <UDID>, or open Simulator.app) " +
                "or set cmp.hotreload.simulatorUdid=<UDID>; list them with: xcrun simctl list devices"
            else "CMP hot reload: ${booted.size} simulators are booted, so \"booted\" is ambiguous:\n" +
                booted.joinToString("\n") { "  $it" } + "\nset cmp.hotreload.simulatorUdid=<UDID> in local.properties",
        )
    }

    /** PID of the app process on this simulator (the bundle path contains the device UDID unless it is "booted"). */
    fun appPid(executable: String): Long? {
        val out = Proc.run(listOf("ps", "-axo", "pid=,command="), echo = false).output
        return findAppPid(out, s.udid, executable)
    }
}
