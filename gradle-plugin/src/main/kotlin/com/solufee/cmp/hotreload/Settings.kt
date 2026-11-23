package com.solufee.cmp.hotreload

import java.io.File

/**
 * One source set of a module in a multiplatform compilation (`commonMain`, `iosMain`, ...): its Kotlin source
 * directories and the fragments it refines (dependsOn). A plain module has a single fragment.
 */
data class HrFragment(val name: String, val dirs: List<String>, val refines: List<String> = emptyList()) : java.io.Serializable

/** One resolved module (configuration-cache safe: plain strings only). */
data class HrModule(
    /** Gradle path, e.g. ":ui". */
    val path: String,
    val kind: ModuleKind,
    /** Absolute Kotlin source directory. */
    val srcDir: String,
    /** Library paths this module links against (in compile order). */
    val deps: List<String>,
    /** Source sets; more than one means a multiplatform (-Xmulti-platform -Xfragments ...) compilation. */
    val fragments: List<HrFragment> = listOf(HrFragment("main", listOf(srcDir))),
    /**
     * #76: the project's own Kotlin compiler plugins for this module (jars of its Kotlin/Native compile's plugin
     * classpath, whatever plugins the build applies) and their `plugin:<id>:<key>=<value>` options,
     * passed to every hot reload compile and the release check. The Compose plugin is not among them: the toolchain's
     * own is used.
     */
    val compilerPlugins: List<String> = emptyList(),
    val pluginOptions: List<String> = emptyList(),
    /**
     * #76: the module's build directory (absolute, from `project.layout.buildDirectory` at configuration); source
     * directories under it are generated. Null when unknown (then nothing counts as generated).
     */
    val buildDir: String? = null,
) : java.io.Serializable {
    /** Every Kotlin source directory of the module. */
    val sourceDirs: List<String> get() = fragments.flatMap { it.dirs }.distinct()

    /** #76: whether [dir] is inside this module's build directory (generated source), by path, not by a name in it. */
    fun isGenerated(dir: String): Boolean = buildDir != null && File(dir).absoluteFile.normalize().startsWith(File(buildDir).absoluteFile.normalize())

    /** Source directories of generators (under the build directory) and the module's own ones. */
    val generatedSourceDirs: List<String> get() = sourceDirs.filter(::isGenerated)
    val ownSourceDirs: List<String> get() = sourceDirs.filterNot(::isGenerated)

    /** Short label used in logs and symbol checks: ":feature:ui" -> "feature-ui". */
    val label: String get() = path.trim(':').replace(':', '-').ifEmpty { "root" }
}

/** Everything the pipeline needs, resolved from toolchain.json, the DSL and properties. */
data class HrSettings(
    val rootDir: String,
    val workDir: String,
    // toolchain.json
    val dist: String,
    val kotlinc: String,
    val compilerJar: String,
    val composePlugin: String,
    val klibList: String,
    val kaldoDylib: String,
    val kaldoSha256: String?,
    val target: String,
    // binaries not yet in toolchain.json (v3)
    val orcRuntime: String,
    val objcUtilsLib: String,
    val llvmHome: String?,
    // daemons
    val useDaemon: Boolean,
    val socketDir: String,
    val javaHome: String,
    // build
    val modules: List<HrModule>,
    val infoPlist: String,
    val linkerOptions: List<String>,
    val keepCompactUnwind: Boolean,
    // simulator
    val port: Int,
    val udid: String,
    val launchLog: String,
    val readyMarker: String?,
    val launchTimeoutSec: Int,
    val launchRetries: Int,
    val reinstall: Boolean,
    // compatibility classifier / warm restart (INTERFACE §10)
    val warmRestart: Boolean,
    /** KONAN_HR_FORCE_RECOMPOSE for the app (toolchain .6+, INTERFACE §7). */
    val forceRecompose: Boolean = true,
    /** Toolchain .8+: how long a reload waits for the app's main thread before it is given up (KONAN_HR_MAIN_WAIT_MS). */
    val mainWaitMs: Int = 2000,
    /** #29/#47: ask for HotReloadStableKeys in hot-reload compiles (used only when the toolchain's Compose compiler knows it). */
    val stableKeys: Boolean = true,
    /** #56: embed the library object in the executable (portable .app) instead of loading it from its file on this Mac. */
    val libsInExecutable: Boolean = false,
    /** #107: run the app with KALDO_PERF_MAP=1 (cmpHotReload { perfMap }, cmp.hotreload.perfMap); default off. */
    val perfMap: Boolean = false,
    /**
     * #124: a library klib's upstream modules count by their ABI fingerprint (metadata + inline function IR, from
     * compat-classify.py `abi-fingerprint`) instead of their klib bytes, so a body-only edit upstream does not recompile
     * the modules downstream. False: by klib bytes (sha256), as up to 0.2.11 (cmp.hotreload.abiFingerprint).
     */
    val abiFingerprint: Boolean = true,
    /** #129: hotReload compiles the library klibs itself (see HotReloadExtension.klibInReload) (cmp.hotreload.klibInReload). */
    val klibInReload: Boolean = true,
    /**
     * #129: build files that feed a module's compile beyond its sources ("module path" -> files: the module's build.gradle(.kts)),
     * and of the whole build (settings, gradle.properties, version catalogs, local.properties, of the root and included builds).
     * Only their metadata is stamped; any change means the module's inputs are checked as before.
     */
    val moduleBuildFiles: Map<String, List<String>> = emptyMap(),
    val globalBuildFiles: List<String> = emptyList(),
    // 0.2.0: Kotlin Multiplatform projects
    /** Klibs from the modules' Gradle dependencies, after the toolchain's own copies were taken out. */
    val extraKlibs: List<String> = emptyList(),
    /** P0/B1: toolchain klibs not linked because the project's newer copy of the same component is in [extraKlibs]. */
    val droppedToolchainKlibs: List<String> = emptyList(),
    /** `pkg.Function` returning a UIViewController; set when the app has no Kotlin main (KMP template). */
    val iosEntry: String? = null,
    /**
     * Compose resources assembled for iosSimulatorArm64, one directory per module that has them (app and
     * libraries); all are copied into the .app's compose-resources/ (each module's files sit in its own package dir).
     */
    val composeResources: List<String> = emptyList(),
    /** #10: hot modules with src/<set>Main/kotlin directories that are not compiled (printed by package). */
    val sourceSetWarnings: List<String> = emptyList(),
    /** Info.plist to write at [infoPlist] when the app module has none (KMP template). */
    val infoPlistContent: String? = null,
    /** #62 (INTERFACE §6.1): the toolchain's first-frame ready marker (runtime.readyMarker, .11+); null on older toolchains. */
    val toolchainReadyMarker: String? = null,
    /** #98: the project's Kotlin Gradle plugin version (part of the build identity). */
    val projectKotlinVersion: String? = null,
) : java.io.Serializable {
    val app: HrModule get() = modules.single { it.kind == ModuleKind.APP }
    val libraries: List<HrModule> get() = modules.filter { it.kind == ModuleKind.LIBRARY }
    val compileSocket: String get() = File(socketDir, "hr-compile.sock").path
    val klibSocket: String get() = File(socketDir, "hr-klib.sock").path
    fun klibOf(m: HrModule): File = File(workDir, "klib/${m.label}/${m.label}.klib")
    val appDir: File get() = File(workDir, "app")
    val hotSymbols: File get() = File(appDir, "hot-symbols.txt")
    val compatDir: File get() = File(workDir, "compat")
    /** "1"/"0": forceRecompose of the app as it was last launched (the DSL value takes effect only at launch). */
    val launchedForceRecomposeFile: File get() = File(workDir, "launched-force-recompose.txt")
    /** forceRecompose of the running app: as launched, or the DSL value when the app was not launched by this version. */
    val runningForceRecompose: Boolean get() = launchedForceRecomposeFile.takeIf { it.isFile }?.readText()?.trim()?.let { it == "1" } ?: forceRecompose
    /** Generated sources (the UIKit entry for iosEntry); added to the app's leaf fragment. */
    val generatedDir: File get() = File(workDir, "generated/kotlin")
    /** Kotlin/Native `klib` tool, used by compat-classify.py snapshot. */
    val klibTool: String get() = File(dist, "bin/klib").path
}
