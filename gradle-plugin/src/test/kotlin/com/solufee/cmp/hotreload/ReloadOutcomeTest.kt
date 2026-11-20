package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #40: the app's answer to a sent patch, from its log (lines as the .8 runtime prints them, REPORT §34). */
class ReloadOutcomeTest {
    private val kaldo = "[kaldo] :: reloadObjects 1 object(s)\n"

    @Test
    fun `NOT applied is a failure, also when the loader already linked the patch`() {
        val o = reloadOutcome(kaldo + "HR reload: NOT applied: the main thread did not take the reload within 2000 ms (busy?)\r\n")
        assertTrue(o is ReloadOutcome.NotApplied, "$o")
        o as ReloadOutcome.NotApplied
        assertEquals(2000L, o.waitMs)
        assertTrue(o.line.startsWith("HR reload: NOT applied"), o.line)
    }

    @Test
    fun `ran on the main queue is applied, with wait and run times`() {
        val o = reloadOutcome(kaldo + "2026-11-21 HR reload: ran on the main queue requestEpochMs=1795166401764 waitMs=0 runMs=37\n")
        assertEquals(ReloadOutcome.Applied("HR reload: ran on the main queue requestEpochMs=1795166401764 waitMs=0 runMs=37", 0, 37), o)
    }

    @Test
    fun `only loader lines or nothing yet`() {
        assertEquals(ReloadOutcome.Loaded, reloadOutcome(kaldo))
        assertEquals(ReloadOutcome.None, reloadOutcome("RENDER_APP tick=3\n"))
        // a NOT applied line wins over a later successful resend in the same window (the sent patch was not applied)
        assertTrue(reloadOutcome("HR reload: NOT applied: … within 500 ms\nHR reload: ran on the main queue waitMs=1 runMs=2\n") is ReloadOutcome.NotApplied)
    }

    @Test
    fun `the patch is failed with the plugin's message, not sent as applied`() {
        val e = PatchNotApplied("CMP hot reload: the app was busy, patch not applied")
        assertTrue(e is org.gradle.api.GradleException)
    }
}
