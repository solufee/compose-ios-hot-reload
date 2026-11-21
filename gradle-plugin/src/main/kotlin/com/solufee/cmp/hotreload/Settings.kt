package com.solufee.cmp.hotreload

import java.io.File
import java.io.Serializable

/**
 * One source set of a module in a multiplatform compilation (`commonMain`, `iosMain`, ...): its Kotlin source
 * directories and the fragments it refines (dependsOn). A plain module has a single fragment.
 */
data class HrFragment(val name: String, val dirs: List<String>, val refines: List<String> = emptyList()) : Serializable

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
) : Serializable {
    /** Every Kotlin source directory of the module. */
    val sourceDirs: List<String> get() = fragments.flatMap { it.dirs }.distinct()

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
    // 0.2.0: Kotlin Multiplatform projects
    /** Klibs from the modules' Gradle dependencies, after the toolchain's own copies were taken out. */
    val extraKlibs: List<String> = emptyList(),
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
) : Serializable {
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
