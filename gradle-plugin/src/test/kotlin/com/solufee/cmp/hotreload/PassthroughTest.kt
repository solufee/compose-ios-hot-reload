package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** #76: the project's compiler plugins in hot reload compiles; generated declaration changes are WARM. */
class PassthroughTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var tmp: File

    private fun settings(modules: List<HrModule>): HrSettings {
        val klibList = File(tmp, "klibs.txt").apply { writeText("# none\n") }
        return HrSettings(
            rootDir = tmp.path, workDir = File(tmp, "w").path, dist = File(tmp, "dist").path, kotlinc = "/k", compilerJar = "/c.jar",
            composePlugin = "/p.jar", klibList = klibList.path, kaldoDylib = "/l.dylib", kaldoSha256 = null, target = "ios_simulator_arm64",
            orcRuntime = "/o.a", objcUtilsLib = "/u.a", llvmHome = null, useDaemon = false, socketDir = "/s", javaHome = "/j",
            modules = modules, infoPlist = "/p", linkerOptions = emptyList(), keepCompactUnwind = true, port = 5567, udid = "booted",
            launchLog = "/l", readyMarker = null, launchTimeoutSec = 1, launchRetries = 1, reinstall = true, warmRestart = true,
        )
    }
    @Test
    fun `the project's Compose plugin and its options are left out, the others kept`() {
        val jars = listOf(File("/g/kotlin-compose-compiler-plugin-embeddable-2.4.21.jar"),
            File("/g/acme-compiler-plugin-embeddable-2.4.21.jar"), File("/g/zeta-compiler-plugin-1.2.3.jar"))
        assertEquals(listOf("acme-compiler-plugin-embeddable-2.4.21.jar", "zeta-compiler-plugin-1.2.3.jar"),
            Kmp.withoutComposeJars(jars).map { it.name })
        val opts = listOf("plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true",
            "plugin:com.example.zeta:verbose=false")
        assertEquals(listOf("plugin:com.example.zeta:verbose=false"), Kmp.withoutComposeOptions(opts))
    }

    @Test
    fun `every hot reload compile gets the module's plugins and options`() {
        val m = HrModule(":shared", ModuleKind.APP, File(tmp, "src").apply { mkdirs() }.path, emptyList(),
            compilerPlugins = listOf("/g/ser.jar"), pluginOptions = listOf("plugin:com.example.zeta:verbose=false"))
        val s = settings(listOf(m))
        val p = Pipeline(s, org.gradle.api.logging.Logging.getLogger("t"))
        assertEquals(listOf("-Xplugin=/g/ser.jar", "-P", "plugin:com.example.zeta:verbose=false"), p.projectPluginArgs(m))
        val lib = p.libraryArgs(m, File(tmp, "x.klib"))
        assertTrue("-Xplugin=/g/ser.jar" in lib && lib.count { it.startsWith("-Xplugin=") } == 2, lib.toString())
    }

    @Test
    fun `an incompatible plugin is named, other failures are left alone`() {
        val jar = File(tmp, "zeta-compiler-plugin-1.2.3.jar")
        java.util.zip.ZipOutputStream(jar.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"))
            z.write("com.example.zeta.plugin.ZetaPluginRegistrar\n".toByteArray()); z.closeEntry()
        }
        val other = File(tmp, "acme-compiler-plugin-embeddable-2.4.21.jar").apply { writeText("x") }
        val out = "exception: java.lang.NoSuchMethodError: 'void org.jetbrains.kotlin.ir.Foo.bar()'\n\tat com.example.zeta.plugin.ir.ZetaTransformer.visit(Z.kt:1)"
        val msg = Pipeline.pluginIncompatibility(out, listOf(other, jar), "2.5.255-SNAPSHOT")!!
        assertTrue(msg.contains("zeta-compiler-plugin-1.2.3.jar does not work with the toolchain's Kotlin/Native compiler 2.5.255-SNAPSHOT"), msg)
        assertTrue(!msg.contains("acme"), msg)
        assertNull(Pipeline.pluginIncompatibility("e: App.kt:3:5 Unresolved reference 'foo'.", listOf(jar), "2.5.255-SNAPSHOT"))
        assertNull(Pipeline.pluginIncompatibility(out, emptyList(), "2.5.255-SNAPSHOT"))
    }

    private val genMeta = """
        package com.example.gen

        import com.example.gen.annotations.Meta

        @Meta("org.example.di.GreeterImpl",tag="m1",binds=["org.example.di.Greeter"])
        public class _Gen_GreeterImpl
        @Meta("org.example.di.Extra",tag="m1")
        public class _Gen_Extra
    """.trimIndent()

    private val module = """
        package com.example.gen

        import com.example.gen.Registry
        public val org.example.di.AppModule.registry : Registry get() = __annotated()
        public fun __annotated(): Registry = registry {
            entry { org.example.di.Extra() }
        }
    """.trimIndent()

    @Test
    fun `top-level declarations of generated output`() {
        assertEquals(listOf("com.example.gen class _Gen_GreeterImpl", "com.example.gen class _Gen_Extra"),
            GeneratedDeclarations.declarations(genMeta))
        assertEquals(listOf("com.example.gen val org.example.di.AppModule.registry", "com.example.gen fun __annotated"),
            GeneratedDeclarations.declarations(module))
    }

    @Test
    fun `an added or removed generated definition is WARM with its name, the same set is not`() {
        val before = GeneratedDeclarations.declarations(genMeta.lines().filter { "Extra" !in it }.joinToString("\n")).toSet()
        val after = GeneratedDeclarations.declarations(genMeta).toSet()
        assertNull(GeneratedDeclarations.reason(after, after))
        val added = GeneratedDeclarations.reason(before, after)!!
        assertTrue(added.startsWith("WARM generated-declarations: generated declarations changed (added 1: _Gen_Extra"), added)
        val removed = GeneratedDeclarations.reason(after, before)!!
        assertTrue(removed.contains("removed 1: _Gen_Extra"), removed)
        assertEquals("generated-declarations", WarmAdvice.parse(added)!!.category)
        assertTrue(WarmAdvice.advice(added).contains("code generator"), WarmAdvice.advice(added))
    }

    @Test
    fun `with a compiler plugin, an annotation added to a class is WARM, body edits are not`() {
        val before = """
            package org.example.kmphr.di

            import com.example.annotations.Single

            interface Greeter { fun greet(): String }

            @Factory(binds = [Greeter::class])
            class GreeterImpl : Greeter {
                override fun greet(): String = "g1"
            }

            class Extra {
                fun tag(): String = "x1"
            }

            @Module
            @ComponentScan("org.example.kmphr.di")
            class AppModule
        """.trimIndent()
        val d0 = PluginAnnotations.declarations(before)
        assertEquals(listOf("org.example.kmphr.di interface Greeter ", "org.example.kmphr.di class GreeterImpl @Factory",
            "org.example.kmphr.di class Extra ", "org.example.kmphr.di class AppModule @ComponentScan,@Module"), d0)
        val body = PluginAnnotations.declarations(before.replace("\"g1\"", "\"g2\"").replace("\"x1\"", "\"x2\""))
        assertNull(PluginAnnotations.reason(d0.toSet(), body.toSet()))
        val single = PluginAnnotations.declarations(before.replace("class Extra {", "@Single\nclass Extra {"))
        assertEquals("WARM compiler-plugin-annotations: annotations a compiler plugin reads changed (Extra: no annotations -> @Single)",
            PluginAnnotations.reason(d0.toSet(), single.toSet()))
        val added = PluginAnnotations.declarations(before + "\n@Single class Cache\n")
        assertTrue(PluginAnnotations.reason(d0.toSet(), added.toSet())!!.contains("Cache added with @Single"))
        assertNull(PluginAnnotations.reason(d0.toSet(), PluginAnnotations.declarations(before + "\nclass Plain\n").toSet()))
        assertTrue(WarmAdvice.advice(PluginAnnotations.reason(d0.toSet(), single.toSet())!!).contains("compiler plugin"))
    }

    @Test
    fun `generated means inside the module's build directory, not a folder named build anywhere in the path`() {
        val root = File(tmp, "build/myapp/shared")          // the project itself lives under a folder named build
        val own = File(root, "src/commonMain/kotlin").apply { mkdirs() }
        val gen = File(root, "build/generated/x/iosSimulatorArm64Main/kotlin").apply { mkdirs() }
        val m = HrModule(":shared", ModuleKind.APP, own.path, emptyList(),
            fragments = listOf(HrFragment("commonMain", listOf(own.path)), HrFragment("iosSimulatorArm64Main", listOf(gen.path))),
            buildDir = File(root, "build").path)
        assertEquals(listOf(gen.path), m.generatedSourceDirs)
        assertEquals(listOf(own.path), m.ownSourceDirs)
        File(own, "A.kt").writeText("package a\n\nclass Own\n")
        File(gen, "G.kt").writeText("package g\n\nclass Gen\n")
        val s = settings(listOf(m))
        assertEquals(listOf(gen.path), GeneratedDeclarations.dirs(s).map { it.path })
        assertEquals(setOf("g class Gen"), GeneratedDeclarations.snapshot(GeneratedDeclarations.dirs(s)))
        // a build directory elsewhere (custom layout.buildDirectory) is still recognised; no build dir known: nothing generated
        assertTrue(m.copy(buildDir = File(tmp, "out").path).generatedSourceDirs.isEmpty())
        assertTrue(m.copy(buildDir = null).generatedSourceDirs.isEmpty())
    }

    @Test
    fun `a direct compile that fails inside a project plugin names the plugin`() {
        val jar = File(tmp, "zeta-compiler-plugin-2.4.21.jar")
        java.util.zip.ZipOutputStream(jar.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"))
            z.write("com.example.zeta.ZetaPluginRegistrar\n".toByteArray()); z.closeEntry()
        }
        // a stand-in compiler that fails like a plugin built for another Kotlin version
        val kotlinc = File(tmp, "kotlinc-native").apply {
            writeText("#!/bin/sh\necho 'exception: java.lang.NoClassDefFoundError: org/jetbrains/kotlin/fir/pipeline/SomethingKt'\n" +
                "echo '\tat com.example.zeta.ZetaIrGenerationExtension.generate(Z.kt:44)'\nexit 1\n")
            setExecutable(true)
        }
        val src = File(tmp, "lib/src").apply { mkdirs() }
        File(src, "L.kt").writeText("package lib\n\nfun l() = 1\n")
        val lib = HrModule(":lib", ModuleKind.LIBRARY, src.path, emptyList(), compilerPlugins = listOf(jar.path))
        val app = HrModule(":app", ModuleKind.APP, File(tmp, "app/src").apply { mkdirs() }.path, listOf(":lib"))
        val s = settings(listOf(app, lib)).copy(kotlinc = kotlinc.path)
        val e = runCatching { Pipeline(s, org.gradle.api.logging.Logging.getLogger("t")).compileLibrary(lib, null) }.exceptionOrNull()
        assertTrue(e?.message?.contains("zeta-compiler-plugin-2.4.21.jar does not work with the toolchain's Kotlin/Native compiler") == true, e?.message)
    }

    @Test
    fun `a failed tool keeps the linker's message when a long traceback follows it`() {
        val output = "ld: warning: -multiply_defined is obsolete\n" +
            "duplicate symbol '_kfun:org/acme/gen/Anchor.kt.org.acme.gen#anchor(){}' in:\n" +
            "    a.a[2](liborg.acme.gen.x1.a.o)\n    b.a[2](liborg.acme.gen.x1.a.o)\nld: 1 duplicate symbols\n" +
            "Traceback (most recent call last):\n" + "  File \"merge.py\", line 1, in run\n".repeat(200)
        val text = Proc.failureText(output)
        assertTrue(text.startsWith("duplicate symbol '_kfun:org/acme/gen/Anchor.kt"), text.take(200))
        assertTrue(text.contains("b.a[2](liborg.acme.gen.x1.a.o)") && text.contains("ld: 1 duplicate symbols"), text.take(400))
        assertTrue(!text.startsWith("ld: warning"), "warnings are not diagnostics")
        assertTrue(text.endsWith(output.takeLast(100)))
        // a short output is returned as it is
        assertEquals("e: x.kt:1 unresolved\n", Proc.failureText("e: x.kt:1 unresolved\n"))
    }
}
