package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Composite builds (0.2.2): modules of included builds, declared with includedModule(...). */
class CompositeBuildTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `an included module without declared source sets uses the default hierarchy and its generated Compose code`() {
        val f = HotReloadPlugin.defaultFragments(File(tmp, "core"))
        assertEquals(listOf("commonMain", "nativeMain", "appleMain", "iosMain", "iosSimulatorArm64Main"), f.map { it.name })
        assertEquals(listOf(emptyList(), listOf("commonMain"), listOf("nativeMain"), listOf("appleMain"), listOf("iosMain")), f.map { it.refines })
        val common = f.first().dirs.map { it.removePrefix(File(tmp, "core").path) }
        assertEquals(listOf("/src/commonMain/kotlin", "/build/generated/compose/resourceGenerator/kotlin/commonMainResourceAccessors",
            "/build/generated/compose/resourceGenerator/kotlin/commonMainResourceCollectors",
            "/build/generated/compose/resourceGenerator/kotlin/commonResClass"), common)
        assertTrue(f.last().dirs.any { it.endsWith("iosSimulatorArm64MainResourceCollectors") })
    }

    @Test
    fun `included modules come first in declaration order, every later module may use them`() {
        val app = HrModule(":app", ModuleKind.APP, "/a", listOf(":feature"))
        val feature = HrModule(":feature", ModuleKind.LIBRARY, "/f", emptyList())
        val util = HrModule(":lib-build:util", ModuleKind.LIBRARY, "/u", emptyList())
        val core = HrModule(":lib-build:core", ModuleKind.LIBRARY, "/c", emptyList())
        val all = HotReloadPlugin.withIncluded(listOf(app, feature), listOf(util, core))
        assertEquals(listOf(":app", ":lib-build:util", ":lib-build:core", ":feature"), all.map { it.path })
        assertEquals(listOf(":lib-build:util", ":lib-build:core", ":feature"), all[0].deps)
        assertEquals(emptyList<String>(), all[1].deps)
        assertEquals(listOf(":lib-build:util"), all[2].deps)
        assertEquals(listOf(":lib-build:util", ":lib-build:core"), all[3].deps)
        assertEquals("lib-build-core", all[2].label)
        assertEquals(listOf(app, feature), HotReloadPlugin.withIncluded(listOf(app, feature), emptyList()))
    }

    @Test
    fun `includedModule keeps the declaration order (the container iterates by name)`() {
        val p = ProjectBuilder.builder().withProjectDir(tmp).build()
        p.pluginManager.apply("com.solufee.cmp.hotreload")
        val ext = p.extensions.getByType(HotReloadExtension::class.java)
        ext.includedModule("lib-build", ":util")
        ext.includedModule("lib-build", "core")
        assertEquals(listOf(":lib-build:util", ":lib-build:core"), ext.includedOrder)
        assertEquals(listOf(":lib-build:core", ":lib-build:util"), ext.includedModules.map { it.name })   // why the order is kept
        assertEquals(":core", ext.includedModules.getByName(":lib-build:core").projectPath.get())
    }

    @Test
    fun `a changed external klib makes the reload warm`() {
        val klib = File(tmp, "core.klib").apply { writeText("v1") }
        val s = HrSettings(
            rootDir = tmp.path, workDir = File(tmp, "w").apply { mkdirs() }.path, dist = "/d", kotlinc = "/k", compilerJar = "/c.jar",
            composePlugin = "/p.jar", klibList = "/k.txt", kaldoDylib = "/l.dylib", kaldoSha256 = null, target = "ios_simulator_arm64",
            orcRuntime = "/o.a", objcUtilsLib = "/u.a", llvmHome = null, useDaemon = false, socketDir = "/s", javaHome = "/j",
            modules = listOf(HrModule(":app", ModuleKind.APP, "/a", emptyList())), infoPlist = "/p", linkerOptions = emptyList(),
            keepCompactUnwind = true, port = 5567, udid = "booted", launchLog = "/l", readyMarker = null, launchTimeoutSec = 1,
            launchRetries = 1, reinstall = true, warmRestart = true, extraKlibs = listOf(klib.path),
        )
        val p = Pipeline(s, org.gradle.api.logging.Logging.getLogger("t"))
        assertEquals(emptyList<String>(), p.externalKlibChanges())          // no manifest yet (packaged by an older plugin)
        p.externalKlibManifest.writeText("${klib.length()}-${klib.lastModified()}  ${klib.path}\n")
        assertEquals(emptyList<String>(), p.externalKlibChanges())
        klib.writeText("v2 longer")
        assertEquals(listOf(klib.path), p.externalKlibChanges())
        val r = externalKlibReasons(listOf(klib.path)).single()
        assertTrue(r.startsWith("WARM external-klibs: 1 changed since packaging (core.klib)"), r)
    }

    @Test
    fun `an includedModule naming a build that is not included is reported, a real included build is accepted`() {
        val root = File(tmp, "root").apply { mkdirs() }
        File(root, "lib-build/core").mkdirs()
        File(root, "lib-build/settings.gradle.kts").writeText("rootProject.name = \"lib-build\"\ninclude(\":core\")\n")
        File(root, "app").mkdirs()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"r\"\nincludeBuild(\"lib-build\")\ninclude(\"app\")\n")
        fun build(modules: String) = File(root, "build.gradle.kts").writeText(
            "plugins { id(\"com.solufee.cmp.hotreload\") }\ncmpHotReload {\n    app(\":app\")\n    $modules\n}\n")
        fun run() = GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments("hotReloadCheckToolchain", "-Pcmp.hotreload.probeHost=false", "--stacktrace").buildAndFail()
        build("includedModule(\"lib-buidl\", \":core\")")
        val wrong = run()
        assertTrue("includedModule(\"lib-buidl\", \":core\"): no included build named lib-buidl (included builds: [lib-build])" in wrong.output, wrong.output)
        build("includedModule(\"lib-build\", \":core\")")
        val ok = run()
        assertTrue("no toolchain configured" in ok.output, ok.output)
        assertTrue("includedModule(" !in ok.output, ok.output)
    }

    private fun klibAt(f: File, unique: String, abi: String = "2.4.0"): File {
        f.parentFile.mkdirs()
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("default/manifest"))
            z.write("unique_name=${unique.replace(":", "\\:")}\nabi_version=$abi\nmetadata_version=2.2.0\n".toByteArray())
            z.closeEntry()
        }
        return f
    }

    @Test
    fun `the included build's own klib of a module compiled from source is left out, by coordinates, location and unique_name`() {
        val lib = File(tmp, "lib-build")
        val core = File(lib, "core")
        val coreKlib = klibAt(File(core, "build/classes/kotlin/iosSimulatorArm64/main/klib/core.klib"), "org.example.lib:core")
        val elsewhere = klibAt(File(lib, "out/core.klib"), "org.example.lib:core")           // same build, other place
        val util = klibAt(File(lib, "util/build/classes/kotlin/iosSimulatorArm64/main/klib/util.klib"), "org.example.lib:util")
        val maven = klibAt(File(tmp, "gradle-cache/core.klib"), "com.other:core")              // another library named core
        fun own(id: String, f: File) = Kmp.isOwnBuildKlib(Kmp.Dependency(id, f), "lib-build", ":core", core, lib)
        assertTrue(own("project :lib-build:core", maven))       // coordinates
        assertTrue(own("x", coreKlib))                            // inside the module directory
        assertTrue(own("x", elsewhere))                           // unique_name ...:core from inside that build
        assertFalse(own("project :lib-build:util", util))         // :util is not declared: linked as its klib
        assertFalse(own("com.other:core:1.0", maven))             // a Maven library with the same short name
    }

    @Test
    fun `a klib from an included build gets the same ABI, metadata and Compose checks as any dependency`() {
        val stdlib = File(tmp, "stdlib/default").apply { mkdirs() }.let { File(it, "manifest").writeText("unique_name=stdlib\nabi_version=2.5.0\nmetadata_version=2.5.0\n"); File(tmp, "stdlib") }
        val future = klibAt(File(tmp, "lib-build/core/build/core.klib"), "org.example.lib:core", abi = "2.7.0")
        val e = assertThrows<GradleException> {
            Kmp.selectKlibs(emptyList(), stdlib, listOf(Kmp.Dependency("project :lib-build:core", future)), "1.13.0-beta01", null)
        }
        assertTrue("project :lib-build:core: built for klib ABI 2.7.0, the toolchain's compiler reads up to 2.5.0" in e.message!!, e.message)
    }

    @Test
    fun `source sets outside the default hierarchy are found, other targets are not, and the warning says what to declare`() {
        val util = File(tmp, "lib-build/util")
        fun kt(set: String, name: String) = File(util, "src/$set/kotlin/$name").apply { parentFile.mkdirs(); writeText("package u\n") }
        kt("commonMain", "A.kt"); kt("iosSharedMain", "Shared.kt"); kt("iosMain", "Ios.kt")
        kt("androidMain", "Android.kt"); kt("iosArm64Main", "Device.kt"); kt("commonTest", "T.kt")
        File(util, "src/emptyMain/kotlin").mkdirs()                                  // no Kotlin file: not a source set
        val fragments = HotReloadPlugin.defaultFragments(util)
        val missing = HotReloadPlugin.uncollectedSourceSets(util, fragments.flatMap { it.dirs })
        assertEquals(listOf(File(util, "src/iosSharedMain/kotlin")), missing)
        val w = HotReloadPlugin.sourceSetWarning("lib-build:util", "includedModule(\"lib-build\", \":util\")", util, fragments, missing)
        assertTrue(w.startsWith("CMP hot reload: warning: lib-build:util has Kotlin source sets that hot reload does not compile: ${File(util, "src/iosSharedMain/kotlin").path}"), w)
        assertTrue("includedModule(\"lib-build\", \":util\") {" in w, w)
        assertTrue("create(\"commonMain\") { srcDir(\"src/commonMain/kotlin\") }" in w, w)
        assertTrue("create(\"iosMain\") { srcDir(\"src/iosMain/kotlin\"); dependsOn(\"commonMain\") }" in w, w)   // chained over present sets only
        assertTrue("create(\"iosSharedMain\") { srcDir(\"src/iosSharedMain/kotlin\"); dependsOn(\"commonMain\") }" in w, w)
        assertEquals(emptyList<File>(), HotReloadPlugin.uncollectedSourceSets(util, fragments.flatMap { it.dirs } + File(util, "src/iosSharedMain/kotlin").path))
    }

    @Test
    fun `the includedModule hint names the build and the project path, whether Gradle quotes the display name or not (#116)`() {
        val snippet = "cmpHotReload { includedModule(\"lib-build\", \":core\") }"
        // from Gradle's ProjectComponentIdentifier (build name, project path): what the plugin passes
        assertTrue(Kmp.includedBuildHint("project ':lib-build:core'", "lib-build", ":core").endsWith(snippet))
        // without it, the display name: unquoted (Gradle 8) and quoted (newer Gradle, the user's report)
        assertTrue(Kmp.includedBuildHint("project :lib-build:core", null, null).endsWith(snippet))
        val quoted = Kmp.includedBuildHint("project ':lib-build:core'", null, null)
        assertTrue(quoted.endsWith(snippet), quoted)
        assertTrue(quoted.startsWith("dependency klibs: project :core comes from the included build lib-build and"), quoted)
        assertFalse("'" in quoted, quoted)
    }

    @Test
    fun `nested project paths keep every level of the path inside the included build (#116)`() {
        // the user's shape: build gooze, project :gooze-widget (display name project ':gooze:gooze-widget')
        assertEquals("gooze" to ":gooze-widget", Kmp.includedModuleHint("project ':gooze:gooze-widget'", "gooze", ":gooze-widget"))
        assertEquals("gooze" to ":gooze-widget", Kmp.includedModuleHint("project ':gooze:gooze-widget'", null, null))
        // two levels inside the included build
        assertEquals("gooze" to ":feature:widget", Kmp.includedModuleHint("project ':gooze:feature:widget'", "gooze", ":feature:widget"))
        assertEquals("gooze" to ":feature:widget", Kmp.includedModuleHint("project :gooze:feature:widget", null, null))
        val line = Kmp.includedBuildHint("project ':gooze:feature:widget'", "gooze", ":feature:widget")
        assertTrue(line.endsWith("cmpHotReload { includedModule(\"gooze\", \":feature:widget\") }"), line)
    }

    @Test
    fun `the dependency report is printed by package and run every time, by a hot reload only when it changed (#116)`() {
        val f = File(tmp, "work/" + com.solufee.cmp.hotreload.tasks.HotReloadTask.REPORT_FILE)
        val a = listOf("dependency klibs: 8 linked", Kmp.includedBuildHint("project :lib-build:util", "lib-build", ":util"))
        assertTrue(com.solufee.cmp.hotreload.tasks.HotReloadTask.reportNow(f, a, always = true))     // package / run
        assertFalse(com.solufee.cmp.hotreload.tasks.HotReloadTask.reportNow(f, a, always = false))   // hotReload, same set
        assertTrue(com.solufee.cmp.hotreload.tasks.HotReloadTask.reportNow(f, a, always = true))     // the next run prints it again
        val b = a + Kmp.includedBuildHint("project :lib-build:core", "lib-build", ":core")
        assertTrue(com.solufee.cmp.hotreload.tasks.HotReloadTask.reportNow(f, b, always = false))    // hotReload, the set changed
        assertFalse(com.solufee.cmp.hotreload.tasks.HotReloadTask.reportNow(f, b, always = false))
    }
}
