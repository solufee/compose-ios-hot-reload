package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** #122: warn when the Kotlin plugin's build statistics (kotlin-profile) slow every build down. */
class KotlinProfileHintTest {
    @TempDir
    lateinit var tmp: File

    private fun profile(files: Int): File = KotlinProfileHint.profileDir(tmp).apply {
        mkdirs()
        repeat(files) { File(this, "f$it.profile").writeText("") }
    }

    @Test
    fun `warns above the threshold only, with the file count and both ways out`() {
        val dir = profile(12)
        assertNull(KotlinProfileHint.warning(dir, null, threshold = 12), "at the threshold: no warning")
        val w = KotlinProfileHint.warning(dir, null, threshold = 11)
        assertNotNull(w)
        assertTrue("holds 12 files" in w!! && "kotlin.internal.collectFUSMetrics=false" in w && "delete the old files" in w, w)
        assertEquals(File(tmp, "kotlin-profile"), dir)
        assertEquals(5000, KotlinProfileHint.THRESHOLD)
    }

    @Test
    fun `no warning when the statistics are switched off, or there is no directory`() {
        val dir = profile(3)
        assertNull(KotlinProfileHint.warning(dir, "false", threshold = 1))
        assertNull(KotlinProfileHint.warning(dir, " FALSE ", threshold = 1))
        assertNotNull(KotlinProfileHint.warning(dir, "true", threshold = 1))
        assertNull(KotlinProfileHint.warning(File(tmp, "missing"), null, threshold = 0))
        // subdirectories are not statistics files
        File(dir, "sub").mkdirs()
        assertEquals(3, KotlinProfileHint.count(dir))
    }

    @Test
    fun `once per session`() {
        val key = "watch:" + tmp.path
        assertEquals("w", KotlinProfileHint.once(key, "w"))
        assertNull(KotlinProfileHint.once(key, "w"))
        assertEquals("w", KotlinProfileHint.once("$key-other", "w"))
        assertNull(KotlinProfileHint.once(key + "-none", null))
        assertEquals("w", KotlinProfileHint.once(key + "-none", "w"), "a session that had nothing to say can still warn later")
    }
}
