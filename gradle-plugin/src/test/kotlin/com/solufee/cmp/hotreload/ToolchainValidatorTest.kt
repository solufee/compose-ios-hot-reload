package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ToolchainValidatorTest {
    @TempDir
    lateinit var dir: File

    private val pinned = PinnedToolchain.current

    /** A toolchain directory whose manifest matches the pinned versions and whose files exist. */
    private fun goodManifest(overrides: Map<String, Any> = emptyMap()): File {
        val klib = File(dir, "klib/stdlib").apply { mkdirs() }
        val list = File(dir, "klibs.txt").apply { writeText(klib.path + "\n") }
        listOf("bin/kotlinc-native", "konan/lib/compiler.jar", "plugin.jar", "libKaldo.iossim.dylib").forEach {
            File(dir, it).apply { parentFile.mkdirs(); writeText("") }
        }
        val values = mutableMapOf<String, Any>(
            "interfaceVersion" to pinned.interfaceVersion,
            "kotlinVersion" to pinned.kotlinVersion,
            "kotlinBase" to pinned.kotlinBaseCommit,
            "kotlinPatch" to pinned.kotlinPatchSha256,
            "kaldoSha" to Tools.sha256(ByteArray(0)),
            "composeVersion" to pinned.composeVersion,
            "composeBase" to pinned.composeBaseCommit,
            "llvmBase" to pinned.llvmBaseCommit,
            "xcode" to pinned.xcode,
        )
        values.putAll(overrides)
        val f = File(dir, TOOLCHAIN_MANIFEST)
        f.writeText(
            """
            {
              "interfaceVersion": ${values["interfaceVersion"]},
              "kotlin": {
                "version": "${values["kotlinVersion"]}",
                "dist": "${dir.path}",
                "kotlincNative": "${dir.path}/bin/kotlinc-native",
                "compilerJar": "${dir.path}/konan/lib/compiler.jar",
                "klibDir": "${dir.path}/klib",
                "baseCommit": "${values["kotlinBase"]}",
                "patchSha256": "${values["kotlinPatch"]}"
              },
              "compose": {
                "version": "${values["composeVersion"]}",
                "pluginJar": "${dir.path}/plugin.jar",
                "klibList": "${list.path}",
                "baseCommit": "${values["composeBase"]}",
                "patchSha256": "${pinned.composePatchSha256}"
              },
              "llvm": { "baseCommit": "${values["llvmBase"]}", "patchSha256": "${pinned.llvmPatchSha256}", "kaldoIossimSha256": "${values["kaldoSha"]}", "kaldoIossim": "${dir.path}/libKaldo.iossim.dylib" },
              "host": { "xcode": "${values["xcode"]}", "iosRuntime": "${pinned.iosRuntime}", "arch": "arm64" },
              "runtime": { "target": "${pinned.target}", "reloadPort": ${pinned.reloadPort} }
            }
            """.trimIndent(),
        )
        return f
    }

    private fun validate(f: File, host: HostFacts = HostFacts(null, null)) =
        ToolchainValidator.validate(Toolchain.parse(f), pinned, host)

    @Test
    fun `matching toolchain has no problems`() {
        assertEquals(emptyList<ToolchainProblem>(), validate(goodManifest(), HostFacts(pinned.xcode, listOf(pinned.iosRuntime))))
    }

    @Test
    fun `newer interfaceVersion asks for a newer plugin`() {
        val p = validate(goodManifest(mapOf("interfaceVersion" to pinned.interfaceVersion + 1))).single()
        assertEquals("interfaceVersion", p.field)
        assertTrue("update the plugin" in p.fix, p.fix)
    }

    @Test
    fun `wrong kotlin commit names the baseline and the patch`() {
        val p = validate(goodManifest(mapOf("kotlinBase" to "0000000000000000000000000000000000000000"))).single()
        assertEquals("kotlin.baseCommit", p.field)
        assertEquals(pinned.kotlinBaseCommit, p.expected)
        assertTrue("kotlin-native-hot-reload.patch" in p.fix, p.fix)
    }

    @Test
    fun `same base commit with a different patch is rejected`() {
        val p = validate(goodManifest(mapOf("kotlinPatch" to "f".repeat(64)))).single()
        assertEquals("kotlin.patchSha256", p.field)
    }

    @Test
    fun `bundle layout is rejected with a fetch hint`() {
        val f = goodManifest()
        f.writeText(f.readText().replaceFirst("{", "{ \"layout\": \"bundle\","))
        val p = validate(f).single()
        assertEquals("layout", p.field)
        assertTrue("fetch-toolchain.py" in p.fix, p.fix)
    }

    @Test
    fun `manifest must match the installed libKaldo`() {
        val p = validate(goodManifest(mapOf("kaldoSha" to "0".repeat(64)))).single()
        assertEquals("llvm.kaldoIossimSha256", p.field)
        assertTrue("rebuilt or replaced" in p.fix, p.fix)
    }

    @Test
    fun `wrong compose version and host xcode are both reported`() {
        val fields = validate(
            goodManifest(mapOf("composeVersion" to "1.9.0")),
            HostFacts("Xcode 26.0 Build version 1", listOf("iOS 26.0")),
        ).map { it.field }
        assertEquals(
            listOf("compose.version", "host.xcode (xcodebuild -version)", "host.iosRuntime (xcrun simctl list runtimes)"),
            fields,
        )
    }

    @Test
    fun `missing files and klibs are reported`() {
        val f = goodManifest()
        File(dir, "plugin.jar").delete()
        File(dir, "klib/stdlib").deleteRecursively()
        val fields = validate(f).map { it.field }
        assertEquals(listOf("compose.pluginJar", "compose.klibList entries"), fields)
    }

    @Test
    fun `report lists expected, found and fix`() {
        val f = goodManifest(mapOf("llvmBase" to "abc"))
        val t = Toolchain.parse(f)
        val text = ToolchainValidator.report(t, f, pinned, ToolchainValidator.validate(t, pinned, HostFacts(null, null)))
        assertTrue("llvm.baseCommit: expected ${pinned.llvmBaseCommit}, found abc" in text, text)
        assertTrue("fix: build LLVM + libKaldo" in text, text)
    }
}
