package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File

/**
 * Installs and launches the packaged app with the runtime switches of INTERFACE §7, and returns once the
 * app is up. The `simctl launch --console-pty` process keeps running and streams the console to the launch log.
 */
class Launcher(private val s: HrSettings, private val logger: Logger) {
    /** [ready]: which rule found the app ready: "marker" (first frame), "state" (state line), "user-marker", "rule" (3 s). */
    data class Started(val attempt: Int, val pid: Long?, val seconds: Double, val ready: String = "rule")

    /** Runtime environment, prefixed with SIMCTL_CHILD_ so simctl passes it to the app. */
    /** INTERFACE §3.3 #2 (SYNTH_EH_FRAME) and #3 (narrowed KALDO_HOT_SYMBOLS) are set here. */
    fun appEnvironment(): Map<String, String> = mapOf(
        "KONAN_ORC_RUNTIME_PATH" to s.orcRuntime,
        "KALDO_SYNTH_EH_FRAME" to "1",
        "KALDO_PERF_MAP" to "1",
        "KALDO_HOT_SYMBOLS" to s.hotSymbols.path,
        "KONAN_HOT_RELOAD_PORT" to s.port.toString(),
        // Toolchain .6+: full recomposition after each reload (INTERFACE §7); passed explicitly either way.
        "KONAN_HR_FORCE_RECOMPOSE" to if (s.forceRecompose) "1" else "0",
        // Toolchain .8+ (#27): every reload runs on the main queue; not taken within this time = not applied (INTERFACE §7).
        "KONAN_HR_RELOAD_ON_MAIN" to "1",
        "KONAN_HR_MAIN_WAIT_MS" to s.mainWaitMs.toString(),
    ).mapKeys { "SIMCTL_CHILD_${it.key}" }

    /**
     * [appArgs] go to the app's argv (e.g. `--hr-state=2,3` for a warm restart). With [readyTokens], the app
     * counts as started only when one console line contains the ready marker and every token (e.g. the
     * restored `page=2` and `count=3`).
     */
    /**
     * [documents]: files written into the app's Documents/ after a reinstall, before launch (warm restart state).
     * [inPlace] (#62, warm restart): when the installed bundle differs from the new one only in its executable, the
     * executable is replaced in the installed bundle (new file, atomic rename, then checked byte for byte) instead of
     * uninstall + install; the app's data container stays, so [documents] are not written again. Any other difference
     * or failed check, or [inPlaceBlocked] (e.g. the data container is gone): uninstall + install as before.
     */
    fun launch(
        pipeline: Pipeline, appArgs: List<String> = emptyList(), readyTokens: List<String> = emptyList(),
        documents: Map<String, ByteArray> = emptyMap(), inPlace: Boolean = false, inPlaceBlocked: String? = null,
    ): Started {
        val info = pipeline.appInfo()
        val bundle = pipeline.appBundle(info)
        if (!bundle.isDirectory) throw GradleException("CMP hot reload: ${bundle.path} not found; run ./gradlew hotReloadPackage first")
        if (!s.hotSymbols.isFile) throw GradleException("CMP hot reload: ${s.hotSymbols.path} missing (INTERFACE §3.3 #3); run ./gradlew hotReloadPackage")
        // #56: the .app loads its library object from this file at start; without it the app could not start.
        pipeline.packagedPayloadFile.takeIf { it.isFile }?.readText()?.trim()?.let { path ->
            if (!File(path).isFile) throw GradleException("CMP hot reload: the packaged app loads its library object from $path at start, " +
                "but the file is gone (build/hotreload was cleaned?); run ./gradlew hotReloadPackage, or package with libsInExecutable = true for a self-contained .app")
        }
        val udid = s.udid
        if (udid == "booted") requireSingleBootedSimulator()
        Proc.run(listOf("xcrun", "simctl", "terminate", udid, info.bundleId), echo = false)
        // #62: read only; the plugin never changes the simulator's accessibility setting. A failed read says nothing. The
        // read (~0.4 s) runs beside the launch and its note is printed once the app is up.
        val axNote = java.util.concurrent.CompletableFuture.supplyAsync {
            val ax = Proc.run(listOf("xcrun", "simctl", "spawn", udid, "defaults", "read", "com.apple.Accessibility", "ApplicationAccessibilityEnabled"), echo = false)
            accessibilityHint(ax.exit, ax.output, udid)
        }
        fun printAxNote() = runCatching { axNote.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()?.let { logger.lifecycle(it) }
        requireFreePort()
        var updatedInPlace = false
        if (s.reinstall) {
            val t0 = System.currentTimeMillis()
            val why = when {
                !inPlace -> "not a warm restart"
                inPlaceBlocked != null -> inPlaceBlocked
                else -> updateInPlace(udid, info, bundle)
            }
            if (why == null) {
                updatedInPlace = true
                logger.lifecycle("install: executable replaced in the installed app (${System.currentTimeMillis() - t0} ms; " +
                    "every other file identical, the installed executable checked byte for byte)")
            } else {
                installedAppFile.delete()
                Proc.run(listOf("xcrun", "simctl", "uninstall", udid, info.bundleId), echo = false)
                Proc.check(listOf("xcrun", "simctl", "install", udid, bundle.path), logger = logger)
                logger.lifecycle("install: uninstall + install (${System.currentTimeMillis() - t0} ms; $why)")
            }
        }
        if (documents.isNotEmpty() && !updatedInPlace) {
            val data = Proc.check(listOf("xcrun", "simctl", "get_app_container", udid, info.bundleId, "data"), echo = false).trim()
            val docs = File(data, "Documents").apply { mkdirs() }
            documents.forEach { (name, bytes) -> File(docs, name).writeBytes(bytes) }
            logger.lifecycle("CMP hot reload: restored ${documents.keys.joinToString { "Documents/$it" }} " +
                "(${documents.values.sumOf { it.size }} bytes) into the new install")
        }
        val log = File(s.launchLog)
        for (attempt in 1..s.launchRetries) {
            log.parentFile?.mkdirs()
            log.writeText("")
            val t0 = System.nanoTime()
            // #62: the console lines arrive as events (LogFollower waits on the log with kqueue), not by reading the log every 0.2 s.
            val follower = LogFollower(log)
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
            val launchEpochMs = System.currentTimeMillis()
            val proc = pb.start()
            val rs = ReadyState(s.readyMarker, toolchainMarker(), readyTokens)
            val deadline = System.currentTimeMillis() + s.launchTimeoutSec * 1000L
            var upSince = 0L
            try {
                while (System.currentTimeMillis() < deadline) {
                    // Wake on the next console line, or for the 3 s rule / a dead launch process.
                    val wait = if (upSince > 0) (upSince + 3000 - System.currentTimeMillis()).coerceIn(1, 500) else 500
                    for (raw in follower.next(wait)) {
                        for (l in raw.split('\r')) {
                            if (l.contains("HR server: Start() failed")) {
                                Proc.run(listOf("xcrun", "simctl", "terminate", udid, info.bundleId), echo = false)
                                throw GradleException("CMP hot reload: the app could not open its reload port ${s.port} (\"HR server: Start() failed\" in ${log.path}); " +
                                    "another app or process is using it. " + portHint())
                            }
                            rs.feed(l)
                        }
                    }
                    val pid = if (rs.wantsPid) appPid(info.executable) else null
                    // The old rule (no state, no marker of the user's): Kotlin main has started and the app stayed alive for 3 s.
                    if (!rs.waitsForLine && pid != null && upSince == 0L) upSince = System.currentTimeMillis()
                    val how = rs.ready(ruleHolds = upSince > 0 && System.currentTimeMillis() - upSince > 3000)
                    if (how != null && pid != null) {
                        val sec = (System.nanoTime() - t0) / 1e9
                        // What the running app was started with: the classifier needs it (#28, @Composable signatures).
                        s.launchedForceRecomposeFile.apply { parentFile.mkdirs() }.writeText(if (s.forceRecompose) "1" else "0")
                        val frame = rs.markerEpochMs?.let { ", launch -> first frame ${it - launchEpochMs} ms" } ?: ""
                        logger.lifecycle(("CMP hot reload: app started (attempt $attempt, pid $pid, %.1f s, ready: ${ReadyState.describe(how)}$frame, " +
                            "port ${s.port}, log ${log.path})").format(sec))
                        printAxNote()
                        return Started(attempt, pid, sec, how)
                    }
                    if (!proc.isAlive) break
                }
            } finally {
                follower.close()
            }
            Proc.run(listOf("kill", "-TERM", "-${proc.pid()}"), echo = false)
            proc.destroy()
            Proc.run(listOf("xcrun", "simctl", "terminate", udid, info.bundleId), echo = false)
            logger.warn("CMP hot reload: attempt $attempt did not reach ${s.readyMarker ?: toolchainMarker() ?: "a running Kotlin main"}" +
                (if (readyTokens.isNotEmpty()) " with ${readyTokens.joinToString(" ")}" else "") + "; " +
                "last log lines:\n" + log.readText().replace('\r', '\n').lines().filter { it.isNotBlank() }.takeLast(5).joinToString("\n"))
        }
        printAxNote()
        throw GradleException("CMP hot reload: app did not start after ${s.launchRetries} attempts; see ${log.path}")
    }

    /**
     * #62: replaces the executable of the installed bundle when nothing else differs. Returns null on success, or why
     * it did not (then the caller reinstalls). The new executable is written next to the installed one and renamed over
     * it (a new file, so no stale code-signing state of the old one), then compared byte for byte with the packaged one.
     */
    /**
     * #62 item 3: the toolchain's first-frame marker, unless the user set their own readyMarker (theirs wins) or the
     * marker is turned off for the app (SIMCTL_CHILD_KONAN_HR_READY_MARKER=0 in the environment, INTERFACE §7).
     */
    private fun toolchainMarker(): String? =
        s.toolchainReadyMarker.takeIf { s.readyMarker == null && System.getenv("SIMCTL_CHILD_KONAN_HR_READY_MARKER") != "0" }

    private val installedAppFile get() = File(s.workDir, "installed-app.txt")

    private fun updateInPlace(udid: String, info: Pipeline.AppInfo, bundle: File): String? {
        // The installed bundle's path from the last in-place update, if it is still there (a reinstall moves it); saves
        // the ~0.16 s of simctl get_app_container. Only for a fixed UDID (the path names the device).
        val cached = installedAppFile.takeIf { it.isFile }?.readLines()?.takeIf { it.size == 3 }?.let { (u, id, path) ->
            File(path).takeIf { udid != "booted" && u == udid && id == info.bundleId && cachedPathUsable(it, udid, info.executable) }
        }
        val installed = cached ?: run {
            val r = Proc.run(listOf("xcrun", "simctl", "get_app_container", udid, info.bundleId, "app"), echo = false)
            if (r.exit != 0) return "the app is not installed"
            File(r.output.trim())
        }
        if (!installed.isDirectory) return "no installed bundle at ${installed.path}"
        installedAppFile.writeText("$udid\n${info.bundleId}\n${installed.path}\n")
        return try {
            sameBundleExceptExecutable(bundle, installed, info.executable)?.let { return it }
            val target = File(installed, info.executable)
            val tmp = File(installed, ".${info.executable}.hr-new")
            tmp.delete()
            if (Proc.run(listOf("cp", "-c", File(bundle, info.executable).path, tmp.path), echo = false).exit != 0) {
                File(bundle, info.executable).copyTo(tmp, overwrite = true)
            }
            tmp.setExecutable(true)
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            if (java.nio.file.Files.mismatch(File(bundle, info.executable).toPath(), target.toPath()) != -1L) {
                "the installed executable differs from the packaged one after the copy"
            } else null
        } catch (e: java.io.IOException) {
            "in-place update failed: ${e.message}"
        }
    }

    companion object {
        /**
         * #62 (#60): the note printed when the simulator has application accessibility on (`defaults read
         * com.apple.Accessibility ApplicationAccessibilityEnabled` prints 1): UIKit then loads the accessibility bundles at
         * every app start, ~1.7 s. Null when it is off, unset or the read failed.
         */
        fun accessibilityHint(exit: Int, output: String, udid: String): String? {
            if (exit != 0 || output.trim() != "1") return null
            return "CMP hot reload: note: this simulator has application accessibility on (com.apple.Accessibility " +
                "ApplicationAccessibilityEnabled = 1), so every app start takes ~1.7 s longer (UIKit loads the accessibility bundles). " +
                "Accessibility clients (Accessibility Inspector, UI test tools) turn it on. To turn it off: " +
                "xcrun simctl spawn $udid defaults write com.apple.Accessibility ApplicationAccessibilityEnabled -bool NO " +
                "(takes effect at the next app start). The plugin only reads this setting."
        }

        /** #62: a remembered installed-bundle path is used only when it still is that device's bundle of this app. */
        fun cachedPathUsable(path: File, udid: String, executable: String): Boolean =
            path.isDirectory && path.name == "$executable.app" && path.path.contains("/Devices/$udid/") &&
                File(path, executable).isFile && File(path, "Info.plist").isFile

        /** #62: whether a console line is the ready line (the marker, if any, and every token). */
        fun readyLine(line: String, marker: String?, tokens: List<String>): Boolean {
            if (marker == null && tokens.isEmpty()) return false
            if (marker != null && !line.contains(marker)) return false
            return tokens.isEmpty() || line.trim().split(Regex("\\s+")).containsAll(tokens)
        }

        /**
         * #62: null when [installed] holds exactly the files of [packaged] and all of them except [executable] are byte for
         * byte the same (Info.plist, signature resources, libKaldo, Compose resources); otherwise the first difference.
         */
        fun sameBundleExceptExecutable(packaged: File, installed: File, executable: String): String? {
            fun files(root: File) = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toSortedSet()
            val a = files(packaged)
            val b = files(installed).filterNot { it.startsWith(".$executable.hr-new") }.toSortedSet()
            if (a != b) return "the installed bundle has other files (${((a - b) + (b - a)).take(3).joinToString()})"
            for (f in a) {
                if (f == executable) continue
                if (java.nio.file.Files.mismatch(File(packaged, f).toPath(), File(installed, f).toPath()) != -1L) return "$f changed"
            }
            return null
        }

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
