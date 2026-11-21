package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** #62: the in-place update only when nothing but the executable differs; the ready line; the log follower. */
class RelaunchTest {
    @TempDir
    lateinit var tmp: File

    private fun bundle(name: String, exe: String = "exe-1", lib: String = "kaldo", res: String? = "r1"): File = File(tmp, name).apply {
        mkdirs()
        File(this, "App").writeText(exe)
        File(this, "Info.plist").writeText("plist")
        File(this, "Frameworks").mkdirs(); File(this, "Frameworks/libKaldo.iossim.dylib").writeText(lib)
        File(this, "_CodeSignature").mkdirs(); File(this, "_CodeSignature/CodeResources").writeText("cr")
        if (res != null) { File(this, "compose-resources").mkdirs(); File(this, "compose-resources/a.cvr").writeText(res) }
    }

    @Test
    fun `only the executable may differ for an in-place update`() {
        val packaged = bundle("new", exe = "exe-2")
        assertNull(Launcher.sameBundleExceptExecutable(packaged, bundle("same", exe = "exe-1"), "App"))
        assertEquals("Frameworks/libKaldo.iossim.dylib changed", Launcher.sameBundleExceptExecutable(packaged, bundle("lib", lib = "kaldo-old"), "App"))
        assertEquals("compose-resources/a.cvr changed", Launcher.sameBundleExceptExecutable(packaged, bundle("res", res = "r0"), "App"))
        val missing = Launcher.sameBundleExceptExecutable(packaged, bundle("nores", res = null), "App")
        assertTrue(missing != null && missing.startsWith("the installed bundle has other files"), missing)
        // a leftover temporary file of an interrupted update is ignored
        val leftover = bundle("left").also { File(it, ".App.hr-new").writeText("x") }
        assertNull(Launcher.sameBundleExceptExecutable(packaged, leftover, "App"))
    }

    @Test
    fun `a remembered installed path is used only while it is that device's bundle`() {
        val dev = File(tmp, "Devices/U1/data/Containers/Bundle/Application/A/App.app").apply { mkdirs() }
        File(dev, "App").writeText("x"); File(dev, "Info.plist").writeText("p")
        assertTrue(Launcher.cachedPathUsable(dev, "U1", "App"))
        assertFalse(Launcher.cachedPathUsable(dev, "U2", "App"))           // another simulator
        assertFalse(Launcher.cachedPathUsable(dev, "U1", "Other"))         // another app
        dev.deleteRecursively()
        assertFalse(Launcher.cachedPathUsable(dev, "U1", "App"))           // reinstalled elsewhere
    }

    @Test
    fun `no in-place update when the app or its data container is gone`() {
        assertEquals("the app is not installed", WarmRestart.inPlaceBlocked(null))
        assertEquals("the app's data container is gone", WarmRestart.inPlaceBlocked(File(tmp, "deleted-container")))
        assertNull(WarmRestart.inPlaceBlocked(File(tmp, "container").apply { mkdirs() }))
    }

    @Test
    fun `accessibility note only when the simulator reads 1`() {
        val note = Launcher.accessibilityHint(0, "1\n", "U1")
        assertTrue(note != null && note.contains("~1.7 s") && note.contains("xcrun simctl spawn U1 defaults write com.apple.Accessibility ApplicationAccessibilityEnabled -bool NO"), note)
        assertNull(Launcher.accessibilityHint(0, "0\n", "U1"))
        // key not set (fresh simulator): defaults exits 1 with "does not exist"; a failed read is silent too
        assertNull(Launcher.accessibilityHint(1, "The domain/default pair of (com.apple.Accessibility, ApplicationAccessibilityEnabled) does not exist", "U1"))
        assertNull(Launcher.accessibilityHint(149, "", "U1"))
    }

    @Test
    fun `ready line needs the marker and every token`() {
        assertTrue(Launcher.readyLine("RENDER_APP page=2 count=3 tick=1", "RENDER_APP", listOf("page=2", "count=3")))
        assertFalse(Launcher.readyLine("RENDER_APP page=2 count=4 tick=1", "RENDER_APP", listOf("page=2", "count=3")))
        assertTrue(Launcher.readyLine("RENDER_APP page=0", "RENDER_APP", emptyList()))
        assertFalse(Launcher.readyLine("calling Konan_start", null, emptyList()))   // no marker: the 3 s rule decides
    }

    @Test
    fun `the follower hands out a single quiet line at once (no output buffering)`() {
        val log = File(tmp, "quiet.log").apply { writeText("") }
        LogFollower(log).use { f ->
            Thread.sleep(300)
            val t0 = System.nanoTime()
            log.appendText("HR launcher: calling Konan_start=0x1\n")   // then nothing more, like a quiet app
            val got = f.next(3000)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(listOf("HR launcher: calling Konan_start=0x1"), got)
            assertTrue(ms < 500, "a lone line arrived after $ms ms")
        }
    }

    @Test
    fun `the follower hands out lines as they are appended`() {
        val log = File(tmp, "launch.log").apply { writeText("") }
        LogFollower(log).use { f ->
            Thread.sleep(300)
            val t0 = System.nanoTime()
            log.appendText("first\nRENDER_APP page=1\n")
            val got = mutableListOf<String>()
            while (got.size < 2 && System.nanoTime() - t0 < 3_000_000_000L) got += f.next(500)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(listOf("first", "RENDER_APP page=1"), got)
            assertTrue(ms < 1000, "lines arrived after $ms ms")
        }
    }
}
