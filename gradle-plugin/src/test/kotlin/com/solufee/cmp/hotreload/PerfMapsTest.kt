package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** #107: the perf maps of app processes the plugin started are deleted once those processes are gone. */
class PerfMapsTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `only recorded pids whose process is gone lose their map, nothing else is touched`() {
        val work = File(tmp, "work")
        val maps = File(tmp, "tmp").apply { mkdirs() }
        fun map(pid: Long) = PerfMaps.mapOf(pid, maps).apply { writeText("x".repeat(100)) }
        val gone = map(101)
        val running = map(102)
        val notOurs = map(103)
        PerfMaps.remember(work, 101)
        PerfMaps.remember(work, 102)
        PerfMaps.remember(work, 101)   // once
        assertEquals(listOf(101L, 102L), PerfMaps.pids(work))

        val deleted = PerfMaps.clean(work, null, maps, alive = { it == 102L })
        assertEquals(1, deleted)
        assertFalse(gone.exists(), "the ended process's map is deleted")
        assertTrue(running.exists(), "a running process keeps its map")
        assertTrue(notOurs.exists(), "a map the plugin did not record is never touched")
        assertEquals(listOf(102L), PerfMaps.pids(work), "the running pid stays recorded")

        assertEquals(1, PerfMaps.clean(work, null, maps, alive = { false }))
        assertFalse(running.exists())
        assertFalse(PerfMaps.record(work).exists(), "nothing left to remember")
        assertTrue(notOurs.exists())
        assertEquals(0, PerfMaps.clean(work, null, maps))
    }

    @Test
    fun `the default map directory is tmp, named after the pid`() {
        assertEquals(File("/tmp/perf-4242.map"), PerfMaps.mapOf(4242))
    }
}
