package com.solufee.cmp.hotreload

import groovy.json.JsonSlurper
import java.io.File

/** Name of the manifest the low-level line writes next to the Kotlin/Native dist (docs/INTERFACE.md §1). */
const val TOOLCHAIN_MANIFEST = "toolchain.json"

/** A parsed toolchain.json. Missing fields are null; [ToolchainValidator] reports them. */
data class Toolchain(
    val manifest: File,
    val interfaceVersion: Int?,
    val kotlinVersion: String?,
    val kotlinBaseCommit: String?,
    val kotlinPatchSha256: String?,
    val kotlinDist: String?,
    val kotlincNative: String?,
    val compilerJar: String?,
    val klibDir: String?,
    val composeVersion: String?,
    val composeBaseCommit: String?,
    val composePatchSha256: String?,
    val composePluginJar: String?,
    val klibList: String?,
    val llvmBaseCommit: String?,
    val llvmPatchSha256: String?,
    val kaldoIossim: String?,
    val kaldoIossimSha256: String?,
    val xcode: String?,
    val iosRuntime: String?,
    val target: String?,
    val reloadPort: Int?,
    /** "local" (absolute paths on this machine) or "bundle" (downloadable, relative paths); v6+. */
    val layout: String? = null,
    /** Top-level "tools" of a fetched local manifest (INTERFACE §9.4, v8): absolute paths. */
    val toolsLlvm: String? = null,
    val toolsOrcRuntime: String? = null,
    val toolsUikitUtils: String? = null,
    /** Set in downloaded toolchains (and in the plugin's pin): e.g. 2.5.255-hotreload.4. */
    val toolchainVersion: String? = null,
) {
    companion object {
        fun parse(manifest: File): Toolchain = parse(manifest, manifest.readText())

        @Suppress("UNCHECKED_CAST")
        fun parse(manifest: File, text: String): Toolchain {
            val root = JsonSlurper().parseText(text) as? Map<String, Any?>
                ?: throw IllegalArgumentException("$manifest is not a JSON object")
            fun obj(name: String) = root[name] as? Map<String, Any?> ?: emptyMap()
            fun Map<String, Any?>.str(key: String) = (this[key] as? String)?.takeIf { it.isNotBlank() }
            fun Map<String, Any?>.int(key: String) = (this[key] as? Number)?.toInt()
            val kotlin = obj("kotlin")
            val compose = obj("compose")
            val llvm = obj("llvm")
            val host = obj("host")
            val runtime = obj("runtime")
            val tools = obj("tools")
            return Toolchain(
                manifest = manifest,
                interfaceVersion = root.int("interfaceVersion"),
                kotlinVersion = kotlin.str("version"),
                kotlinBaseCommit = kotlin.str("baseCommit"),
                kotlinPatchSha256 = kotlin.str("patchSha256"),
                kotlinDist = kotlin.str("dist"),
                kotlincNative = kotlin.str("kotlincNative"),
                compilerJar = kotlin.str("compilerJar"),
                klibDir = kotlin.str("klibDir"),
                composeVersion = compose.str("version"),
                composeBaseCommit = compose.str("baseCommit"),
                composePatchSha256 = compose.str("patchSha256"),
                composePluginJar = compose.str("pluginJar"),
                klibList = compose.str("klibList"),
                llvmBaseCommit = llvm.str("baseCommit"),
                llvmPatchSha256 = llvm.str("patchSha256"),
                kaldoIossim = llvm.str("kaldoIossim"),
                kaldoIossimSha256 = llvm.str("kaldoIossimSha256"),
                xcode = host.str("xcode"),
                iosRuntime = host.str("iosRuntime"),
                target = runtime.str("target"),
                reloadPort = runtime.int("reloadPort"),
                layout = root.str("layout"),
                toolsLlvm = tools.str("llvmTools"),
                toolsOrcRuntime = tools.str("orcRuntime"),
                toolsUikitUtils = tools.str("uikitUtils"),
                toolchainVersion = root.str("toolchainVersion"),
            )
        }
    }
}

/** The toolchain this plugin build is pinned to (resource pinned-toolchain.json). */
data class PinnedToolchain(
    val interfaceVersion: Int,
    val kotlinVersion: String,
    val kotlinBaseCommit: String,
    val kotlinPatchSha256: String,
    val composeVersion: String,
    val composeBaseCommit: String,
    val composePatchSha256: String,
    val llvmBaseCommit: String,
    val llvmPatchSha256: String,
    val xcode: String,
    val iosRuntime: String,
    val target: String,
    val reloadPort: Int,
    /** The downloadable toolchain release this plugin version is built for. */
    val toolchainVersion: String? = null,
) {
    companion object {
        private const val RESOURCE = "/com/solufee/cmp/hotreload/pinned-toolchain.json"

        val current: PinnedToolchain by lazy {
            val text = PinnedToolchain::class.java.getResourceAsStream(RESOURCE)!!.bufferedReader().use { it.readText() }
            val t = Toolchain.parse(File(RESOURCE), text)
            PinnedToolchain(
                interfaceVersion = t.interfaceVersion!!,
                kotlinVersion = t.kotlinVersion!!,
                kotlinBaseCommit = t.kotlinBaseCommit!!,
                kotlinPatchSha256 = t.kotlinPatchSha256!!,
                composeVersion = t.composeVersion!!,
                composeBaseCommit = t.composeBaseCommit!!,
                composePatchSha256 = t.composePatchSha256!!,
                llvmBaseCommit = t.llvmBaseCommit!!,
                llvmPatchSha256 = t.llvmPatchSha256!!,
                xcode = t.xcode!!,
                iosRuntime = t.iosRuntime!!,
                target = t.target!!,
                reloadPort = t.reloadPort!!,
                toolchainVersion = t.toolchainVersion,
            )
        }
    }
}

data class ToolchainProblem(val field: String, val expected: String, val actual: String, val fix: String)

/** What the host actually has, probed by the check task (null = could not probe). */
data class HostFacts(val xcode: String?, val iosRuntimes: List<String>?)

object ToolchainValidator {
    private const val REGEN =
        "regenerate the manifest with scripts/gen-toolchain.sh (KOTLIN_DIST, COMPOSE_PLUGIN_JAR, KLIBLIST)"

    fun validate(t: Toolchain, pinned: PinnedToolchain, host: HostFacts): List<ToolchainProblem> {
        val p = mutableListOf<ToolchainProblem>()
        fun eq(field: String, expected: String, actual: String?, fix: String) {
            if (actual != expected) p += ToolchainProblem(field, expected, actual ?: "<missing>", fix)
        }
        fun exists(field: String, path: String?, fix: String) {
            when {
                path == null -> p += ToolchainProblem(field, "an existing path", "<missing>", "$fix; or $REGEN")
                !File(path).exists() -> p += ToolchainProblem(field, "an existing path", path, fix)
            }
        }

        val iv = t.interfaceVersion
        if (iv != pinned.interfaceVersion) {
            p += ToolchainProblem(
                "interfaceVersion", pinned.interfaceVersion.toString(), iv?.toString() ?: "<missing>",
                if (iv != null && iv > pinned.interfaceVersion)
                    "the toolchain is newer than this plugin; update the plugin to one built for interfaceVersion $iv"
                else
                    "rebuild the toolchain from the baselines in README.md and $REGEN",
            )
        }
        if (t.layout != null && t.layout != "local") {
            p += ToolchainProblem(
                "layout", "local", t.layout,
                "this plugin version reads only local toolchains; unpack the bundle with scripts/fetch-toolchain.py " +
                    "and point toolchainDir at the localized dist (its toolchain.json has layout \"local\")",
            )
        }
        val kotlinFix = "build Kotlin/Native from JetBrains/kotlin@${pinned.kotlinBaseCommit.take(12)} + " +
            "patches/kotlin-native-hot-reload.patch (README.md, step 2), then $REGEN"
        eq("kotlin.version", pinned.kotlinVersion, t.kotlinVersion, kotlinFix)
        eq("kotlin.baseCommit", pinned.kotlinBaseCommit, t.kotlinBaseCommit, kotlinFix)
        eq("kotlin.patchSha256", pinned.kotlinPatchSha256, t.kotlinPatchSha256, kotlinFix)
        val composeFix = "build Compose from JetBrains/compose-multiplatform-core@${pinned.composeBaseCommit.take(12)} + " +
            "patches/compose-multiplatform-core-build.patch (README.md, step 3), then $REGEN"
        eq("compose.version", pinned.composeVersion, t.composeVersion, composeFix)
        eq("compose.baseCommit", pinned.composeBaseCommit, t.composeBaseCommit, composeFix)
        eq("compose.patchSha256", pinned.composePatchSha256, t.composePatchSha256, composeFix)
        val llvmFix = "build LLVM + libKaldo from llvm-project-kotlin@${pinned.llvmBaseCommit.take(12)} + " +
            "patches/llvm-kaldo.patch (README.md, step 1), then $REGEN"
        eq("llvm.baseCommit", pinned.llvmBaseCommit, t.llvmBaseCommit, llvmFix)
        eq("llvm.patchSha256", pinned.llvmPatchSha256, t.llvmPatchSha256, llvmFix)
        eq("runtime.target", pinned.target, t.target, "only ${pinned.target} is supported; $REGEN")
        val xcodeFix = "install ${pinned.xcode} and select it with `xcode-select -s`, then $REGEN"
        eq("host.xcode (manifest)", pinned.xcode, t.xcode, xcodeFix)
        if (host.xcode != null) eq("host.xcode (xcodebuild -version)", pinned.xcode, host.xcode, xcodeFix)
        if (host.iosRuntimes != null && pinned.iosRuntime !in host.iosRuntimes) {
            p += ToolchainProblem(
                "host.iosRuntime (xcrun simctl list runtimes)", pinned.iosRuntime,
                host.iosRuntimes.joinToString().ifEmpty { "<none>" },
                "install the ${pinned.iosRuntime} simulator runtime in Xcode > Settings > Components",
            )
        }

        exists("kotlin.kotlincNative", t.kotlincNative, "point toolchainDir at a Kotlin/Native dist built with `./gradlew :kotlin-native:dist`")
        exists("kotlin.compilerJar", t.compilerJar, "the dist is incomplete; rerun `./gradlew :kotlin-native:dist`")
        exists("kotlin.klibDir", t.klibDir, "the dist has no klib/; rerun `./gradlew :kotlin-native:dist distPlatformLibs`")
        exists("compose.pluginJar", t.composePluginJar, "build the Compose compiler plugin jar (README.md, step 3)")
        exists("llvm.kaldoIossim", t.kaldoIossim, "build libKaldo.iossim.dylib (README.md, step 1)")
        // INTERFACE §3.3 #7: the manifest must describe the library actually installed.
        t.kaldoIossim?.let(::File)?.takeIf { it.isFile }?.let { lib ->
            val actual = Tools.sha256(lib.readBytes())
            if (t.kaldoIossimSha256 != actual) {
                p += ToolchainProblem(
                    "llvm.kaldoIossimSha256", "sha256 of ${lib.path} = $actual", t.kaldoIossimSha256 ?: "<missing>",
                    "libKaldo was rebuilt or replaced after the manifest was generated; $REGEN",
                )
            }
        }
        exists("compose.klibList", t.klibList, "generate the klib list (README.md, step 3)")
        t.klibList?.let(::File)?.takeIf { it.isFile }?.let { list ->
            val missing = list.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .filterNot { File(it).exists() }
            if (missing.isNotEmpty()) {
                p += ToolchainProblem(
                    "compose.klibList entries", "every klib in ${list.path} exists",
                    "${missing.size} missing, e.g. ${missing.take(3).joinToString()}",
                    "rebuild the Compose klibs or regenerate the list (README.md, step 3)",
                )
            }
        }
        return p
    }

    fun report(t: Toolchain?, manifest: File, pinned: PinnedToolchain, problems: List<ToolchainProblem>): String =
        buildString {
            appendLine("CMP hot reload: the toolchain does not match what plugin com.solufee.cmp.hotreload is pinned to.")
            appendLine("  manifest: ${manifest.path}" + (t?.toolchainVersion?.let { " (downloaded toolchain $it)" } ?: ""))
            appendLine(
                "  pinned:   interfaceVersion=${pinned.interfaceVersion}, Kotlin ${pinned.kotlinVersion}@${pinned.kotlinBaseCommit.take(12)}, " +
                    "Compose ${pinned.composeVersion}@${pinned.composeBaseCommit.take(12)}, LLVM@${pinned.llvmBaseCommit.take(12)}, ${pinned.xcode}",
            )
            appendLine(
                "            patches: kotlin ${pinned.kotlinPatchSha256.take(12)}, compose ${pinned.composePatchSha256.take(12)}, llvm ${pinned.llvmPatchSha256.take(12)} (sha256)",
            )
            if (pinned.toolchainVersion != null && t?.toolchainVersion != null && t.toolchainVersion != pinned.toolchainVersion) {
                appendLine("  This plugin works with toolchain ${pinned.toolchainVersion}; download it with scripts/fetch-toolchain.py")
                appendLine("  (docs/TRYOUT.md §2) and point toolchainDir at <cache>/${pinned.toolchainVersion}/kotlin-native.")
            }
            for (pr in problems) {
                appendLine("  - ${pr.field}: expected ${pr.expected}, found ${pr.actual}")
                appendLine("      fix: ${pr.fix}")
            }
            if (t == null) appendLine("  (manifest could not be read)")
        }
}
