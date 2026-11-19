package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PipelineTest {
    @TempDir
    lateinit var tmp: File

    private val app = HrModule(":app", ModuleKind.APP, "/app", listOf(":ui", ":ui.core"))
    private val ui = HrModule(":ui", ModuleKind.LIBRARY, "/ui", emptyList())
    private val core = HrModule(":ui.core", ModuleKind.LIBRARY, "/core", listOf(":ui"))
    private val packages = mapOf(app to setOf(""), ui to setOf("expui"), core to setOf("expui.core"))

    @Test
    fun `objects are classified by the longest matching package`() {
        assertEquals(ui, Pipeline.classify(setOf("_kfun:expui#UiMark(kotlin.Int){}"), packages))
        assertEquals(core, Pipeline.classify(setOf("_kfun:expui.core#f(){}", "_kfun:expui.core.Box#get(){}"), packages))
        assertEquals(ui, Pipeline.classify(setOf("_kfun:expui.ComposableSingletons\$UiKt#<get-lambda-1>(){}"), packages))
        assertEquals(app, Pipeline.classify(setOf("_kfun:#main(){}"), packages))
        // Root-package classes have no '#' right after the prefix but still belong to the root-package module.
        assertEquals(app, Pipeline.classify(setOf("_kfun:AppDelegate#application(){}", "_kfun:#main(){}"), packages))
        assertNull(Pipeline.classify(setOf("_kclass:Foo", "_main"), mapOf(ui to setOf("expui"))))
    }

    @Test
    fun `source packages are read from package directives`() {
        File(tmp, "a.kt").writeText("// header\npackage expui\n\nfun a() {}\n")
        File(tmp, "sub").mkdirs()
        File(tmp, "sub/b.kt").writeText("@file:Suppress(\"x\")\npackage expui.`sub`\n")
        File(tmp, "c.kt").writeText("fun main() {}\n")
        assertEquals(setOf("expui", "expui.sub", ""), Pipeline.sourcePackages(tmp))
    }

    @Test
    fun `module labels and task names`() {
        assertEquals("ui", ui.label)
        assertEquals("feature-ui", HrModule(":feature:ui", ModuleKind.LIBRARY, "/x", emptyList()).label)
        assertEquals("FeatureUi", HotReloadPlugin.taskSuffix(HrModule(":feature:ui", ModuleKind.LIBRARY, "/x", emptyList())))
    }

    @Test
    fun `bundled tools match their recorded sha256`() {
        val t = Tools.extract(File(tmp, "work"), null)
        for (name in Tools.NAMES) assertEquals(t.shas[name], Tools.sha256(File(t.dir, name).readBytes()), name)
        assertEquals(Tools.NAMES.toSet(), t.shas.keys)
    }

    @Test
    fun `binaries come from the fetched toolchain layout unless set explicitly`(@org.junit.jupiter.api.io.TempDir tmp: File) {
        val root = File(tmp, "cache/2.5.255-hotreload.4")
        val dist = File(root, "kotlin-native").apply { mkdirs() }
        File(root, "orc-runtime").mkdirs(); File(root, "orc-runtime/liborc_rt_iossim_arm64.a").writeText("x")
        File(root, "cmp-uikit-utils").mkdirs(); File(root, "cmp-uikit-utils/libCMPUIKitObjcUtils.a").writeText("x")
        File(root, "llvm-tools/bin").mkdirs(); File(root, "llvm-tools/bin/llvm-objcopy").writeText("x")
        val project = File(tmp, "p")
        val fetched = HotReloadPlugin.resolveBinaries(dist.path, null, null, null, project)
        org.junit.jupiter.api.Assertions.assertEquals(File(root, "orc-runtime/liborc_rt_iossim_arm64.a").path, fetched.orcRuntime)
        org.junit.jupiter.api.Assertions.assertEquals(File(root, "cmp-uikit-utils/libCMPUIKitObjcUtils.a").path, fetched.objcUtilsLib)
        org.junit.jupiter.api.Assertions.assertEquals(File(root, "llvm-tools").path, fetched.llvmHome)
        val explicit = HotReloadPlugin.resolveBinaries(dist.path, File("/x/orc.a"), null, File("/x/llvm"), project)
        org.junit.jupiter.api.Assertions.assertEquals("/x/orc.a", explicit.orcRuntime)
        org.junit.jupiter.api.Assertions.assertEquals("/x/llvm", explicit.llvmHome)
        val listed = HotReloadPlugin.resolveBinaries(dist.path, null, null, null, project, listOf("/m/orc.a", "/m/u.a", "/m/llvm-tools"))
        org.junit.jupiter.api.Assertions.assertEquals("/m/orc.a", listed.orcRuntime)
        org.junit.jupiter.api.Assertions.assertEquals("/m/u.a", listed.objcUtilsLib)
        org.junit.jupiter.api.Assertions.assertEquals("/m/llvm-tools", listed.llvmHome)
        val local = HotReloadPlugin.resolveBinaries(File(tmp, "built/dist").path, null, null, null, project)
        org.junit.jupiter.api.Assertions.assertEquals(File(project, "tools/liborc_rt_iossim_arm64.a").path, local.orcRuntime)
        org.junit.jupiter.api.Assertions.assertEquals(null, local.llvmHome)
    }
}
