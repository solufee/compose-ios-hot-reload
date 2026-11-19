package com.solufee.cmp.hotreload

import java.io.File
import java.io.Serializable

/** One resolved module (configuration-cache safe: plain strings only). */
data class HrModule(
    /** Gradle path, e.g. ":ui". */
    val path: String,
    val kind: ModuleKind,
    /** Absolute Kotlin source directory. */
    val srcDir: String,
    /** Library paths this module links against (in compile order). */
    val deps: List<String>,
) : Serializable {
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
) : Serializable {
    val app: HrModule get() = modules.single { it.kind == ModuleKind.APP }
    val libraries: List<HrModule> get() = modules.filter { it.kind == ModuleKind.LIBRARY }
    val compileSocket: String get() = File(socketDir, "hr-compile.sock").path
    val klibSocket: String get() = File(socketDir, "hr-klib.sock").path
    fun klibOf(m: HrModule): File = File(workDir, "klib/${m.label}/${m.label}.klib")
    val appDir: File get() = File(workDir, "app")
    val hotSymbols: File get() = File(appDir, "hot-symbols.txt")
    val compatDir: File get() = File(workDir, "compat")
    /** Kotlin/Native `klib` tool, used by compat-classify.py snapshot. */
    val klibTool: String get() = File(dist, "bin/klib").path
}
