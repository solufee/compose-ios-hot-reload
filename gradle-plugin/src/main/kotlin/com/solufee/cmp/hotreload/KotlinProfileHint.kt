package com.solufee.cmp.hotreload

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * #122: the Kotlin Gradle plugin's build statistics (FUS). At the end of every build it lists and sorts the files in
 * <Gradle user home>/kotlin-profile, which grows by a few files per build and is never pruned. With tens of thousands of
 * files this keeps Gradle busy for seconds after a hot reload's patch is on screen, and the next save waits for it.
 * The plugin only warns: it neither deletes that directory (it is shared by all the user's builds) nor changes the property.
 */
object KotlinProfileHint {
    const val THRESHOLD = 5000
    const val PROPERTY = "kotlin.internal.collectFUSMetrics"

    fun profileDir(gradleUserHome: File): File = File(gradleUserHome, "kotlin-profile")

    /** Files directly in [dir] (the statistics are flat files); 0 when it does not exist. */
    fun count(dir: File): Int = dir.listFiles()?.count { it.isFile } ?: 0

    /** The warning, or null: statistics switched off ([collectFus] is "false"), or no more than [threshold] files. */
    fun warning(dir: File, collectFus: String?, threshold: Int = THRESHOLD): String? {
        if (collectFus?.trim().equals("false", ignoreCase = true)) return null
        val n = count(dir)
        if (n <= threshold) return null
        return "CMP hot reload: warning: ${dir.path} holds $n files (the Kotlin Gradle plugin's build statistics, shared by all " +
            "your Gradle builds). The Kotlin plugin goes through them at the end of every build, so after each hot reload Gradle " +
            "stays busy for seconds after the patch is on screen, and the next save waits for it. To avoid it: add " +
            "$PROPERTY=false to gradle.properties (the project's or ~/.gradle/gradle.properties), or delete the old files in " +
            "that directory yourself. The hot reload plugin changes neither."
    }

    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** [message] the first time for [session] in this Gradle daemon, null after that (and for a null message). */
    fun once(session: String, message: String?): String? = if (message != null && warned.add(session)) message else null
}
