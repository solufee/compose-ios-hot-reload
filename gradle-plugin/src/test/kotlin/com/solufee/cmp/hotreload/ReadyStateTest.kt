package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

/** #62 item 3 (INTERFACE §6.1): the first-frame marker, the old rules, and who wins. */
class ReadyStateTest {
    private val m = "HR READY first-frame"

    private fun state(user: String? = null, toolchain: String? = m, tokens: List<String> = emptyList(), vararg lines: String) =
        ReadyState(user, toolchain, tokens).apply { lines.forEach(::feed) }

    @Test
    fun `the marker line makes the app ready and gives the first frame's time`() {
        val s = state(lines = arrayOf("HR launcher: calling Konan_start=0x1", "HR READY first-frame epochMs=1795240093197"))
        assertEquals("marker", s.ready(ruleHolds = false))
        assertEquals(1795240093197L, s.markerEpochMs)
        assertEquals("marker", state(lines = arrayOf("HR READY first-frame")).ready(false))          // no fields
        assertEquals("marker", state(lines = arrayOf("HR READY first-frame epochMs=1 later=x")).ready(false)) // fields added later
    }

    @Test
    fun `only the start of the line counts`() {
        assertNull(state(lines = arrayOf("log: HR READY first-frame epochMs=1")).ready(false))
        assertNull(state(lines = arrayOf("HR READY first-frames epochMs=1")).ready(false))
        assertNull(state(lines = arrayOf("HR READY first")).ready(false))
    }

    @Test
    fun `without the marker the old 3 s rule still makes it ready (whichever comes first)`() {
        val s = state(lines = arrayOf("HR launcher: calling Konan_start=0x1"))
        assertNull(s.ready(ruleHolds = false))
        assertEquals("rule", s.ready(ruleHolds = true))
        // a toolchain without runtime.readyMarker: as before
        assertEquals("rule", state(toolchain = null, lines = arrayOf("calling Konan_start")).ready(true))
        assertNull(state(toolchain = null, lines = arrayOf("HR READY first-frame epochMs=1")).ready(false))
    }

    @Test
    fun `warm restart tokens are checked when the marker comes`() {
        val tokens = listOf("page=2", "count=3")
        assertNull(state(tokens = tokens, lines = arrayOf("HR READY first-frame epochMs=1")).ready(false))
        assertEquals("marker", state(tokens = tokens, lines = arrayOf("HR STATE restore page=2 count=3", "HR READY first-frame epochMs=1")).ready(false))
        assertNull(state(tokens = tokens, lines = arrayOf("HR STATE restore page=2 count=4", "HR READY first-frame")).ready(true))
    }

    @Test
    fun `with the toolchain marker a state line alone is not ready (it comes before the first frame)`() {
        val tokens = listOf("page=2", "count=3")
        val s = state(tokens = tokens, lines = arrayOf("HR launcher: calling Konan_start=0x1", "HR STATE restore page=2 count=3"))
        assertNull(s.ready(ruleHolds = false))
        assertEquals("rule", s.ready(ruleHolds = true))          // UIKit-only / slow first frame: the 3 s rule, tokens seen
        assertNull(state(tokens = tokens, lines = arrayOf("calling Konan_start")).ready(true))   // 3 s but no restored state
        // before .11 (no toolchain marker) the state line is the readiness, as in 0.2.6
        assertEquals("state", state(toolchain = null, tokens = tokens, lines = arrayOf("RENDER_APP ab2 tick=0 count=3 page=2")).ready(false))
    }

    @Test
    fun `the user's readyMarker wins over the toolchain's`() {
        val s = state(user = "APP UP", lines = arrayOf("HR READY first-frame epochMs=1"))
        assertNull(s.ready(ruleHolds = true))
        assertEquals("user-marker", state(user = "APP UP", lines = arrayOf("x APP UP y")).ready(false))
    }

    @Test
    fun `the manifest's runtime readyMarker is read`() {
        val text = """{"interfaceVersion":8,"runtime":{"target":"ios_simulator_arm64","readyMarker":"HR READY first-frame"}}"""
        assertEquals("HR READY first-frame", Toolchain.parse(File("toolchain.json"), text).readyMarker)
        assertNull(Toolchain.parse(File("toolchain.json"), """{"runtime":{"target":"ios_simulator_arm64"}}""").readyMarker)
    }
}
