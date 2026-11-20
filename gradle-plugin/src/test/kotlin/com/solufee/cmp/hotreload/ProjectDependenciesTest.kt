package com.solufee.cmp.hotreload

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** KMP modules depending on KMP modules (0.2.1): libraries found transitively, each after what it depends on. */
class ProjectDependenciesTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `project dependencies are transitive, dependencies first, cycles ignored`() {
        val root = ProjectBuilder.builder().withProjectDir(tmp).withName("root").build()
        fun module(name: String) = ProjectBuilder.builder().withName(name).withParent(root).withProjectDir(File(tmp, name)).build()
            .also { it.configurations.create(Kmp.DEPENDENCY_CONFIGURATION) }
        val app = module("shared")
        val core = module("core")
        val base = module("base")
        val util = module("util")
        fun dep(from: org.gradle.api.Project, to: org.gradle.api.Project) =
            from.dependencies.add(Kmp.DEPENDENCY_CONFIGURATION, from.dependencies.project(mapOf("path" to to.path)))
        dep(app, core)        // shared -> core -> base -> util, and shared -> util directly
        dep(core, base)
        dep(base, util)
        dep(app, util)
        dep(util, app)        // a cycle back to the app is ignored
        assertEquals(listOf(":util", ":base", ":core"), HotReloadPlugin.projectDependencies(app).map { it.path })
        // Through the cycle (util -> shared) everything is reachable from base, but never base itself, and it terminates.
        assertEquals(listOf(":core", ":shared", ":util"), HotReloadPlugin.projectDependencies(base).map { it.path })
    }
}
