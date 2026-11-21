package com.solufee.cmp.hotreload

/**
 * #62 item 3 (INTERFACE §6.1): when a launched app counts as ready, fed with the launch log's lines (`\r` already split).
 *
 * - [userMarker] (DSL `simulator.readyMarker`): a line containing it and every token, as before; it wins over the
 *   toolchain's marker.
 * - otherwise, with [toolchainMarker] (manifest `runtime.readyMarker`, .11+): a line matching `^<marker>( |$)` (the
 *   first frame was committed) **or** Kotlin main started (`calling Konan_start`) and the app stayed alive 3 s,
 *   whichever comes first (UIKit-only apps and very slow first frames take the 3 s rule); the warm restart [tokens]
 *   must have been seen on one line at that moment. A state line alone does not count: it is printed when Kotlin main
 *   starts, before anything is drawn.
 * - without a toolchain marker (before .11), as before: with [tokens] one line holding every token; without, the 3 s
 *   rule (the caller tracks the time).
 */
class ReadyState(private val userMarker: String?, toolchainMarker: String?, private val tokens: List<String>) {
    private val marker: Regex? = toolchainMarker?.takeIf { userMarker == null }?.let { Regex("^" + Regex.escape(it) + "( |$)") }
    var kotlinStarted = false; private set
    var markerSeen = false; private set
    /** epochMs of the marker line, when it carries one. */
    var markerEpochMs: Long? = null; private set
    private var tokensSeen = tokens.isEmpty()
    private var lineReady = false

    /** True when the readiness is a line (the user's marker, or the state tokens before .11), not 3 s after Kotlin main. */
    val waitsForLine: Boolean get() = marker == null && (tokens.isNotEmpty() || userMarker != null)

    fun feed(line: String) {
        if (line.contains("calling Konan_start")) kotlinStarted = true
        if (tokens.isNotEmpty() && line.trim().split(WS).containsAll(tokens)) tokensSeen = true
        if (Launcher.readyLine(line, userMarker, tokens)) lineReady = true
        if (marker != null && !markerSeen && marker.containsMatchIn(line)) {
            markerSeen = true
            markerEpochMs = EPOCH.find(line)?.groupValues?.get(1)?.toLongOrNull()
        }
    }

    private val markerReady: Boolean get() = markerSeen && tokensSeen

    /** Whether the caller should look up the app's pid now (it is needed by every way of being ready). */
    val wantsPid: Boolean get() = markerReady || (waitsForLine && lineReady) || (!waitsForLine && kotlinStarted)

    /** Which rule finds the app ready, or null; [ruleHolds]: Kotlin main started and the app has lived 3 s since. */
    fun ready(ruleHolds: Boolean): String? = when {
        markerReady -> "marker"
        waitsForLine -> if (lineReady) (if (userMarker != null) "user-marker" else "state") else null
        ruleHolds && tokensSeen -> "rule"
        else -> null
    }

    companion object {
        private val WS = Regex("\\s+")
        private val EPOCH = Regex("\\bepochMs=(\\d+)")

        fun describe(how: String): String = when (how) {
            "marker" -> "first frame (toolchain marker)"
            "state" -> "restored state on the console"
            "user-marker" -> "your readyMarker"
            else -> "Kotlin main alive 3 s"
        }
    }
}
