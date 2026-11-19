package com.solufee.cmp.hotreload

import com.solufee.cmp.hotreload.tasks.CheckToolchainTask
import com.solufee.cmp.hotreload.tasks.CleanTask
import com.solufee.cmp.hotreload.tasks.CompileKlibTask
import com.solufee.cmp.hotreload.tasks.DaemonStartTask
import com.solufee.cmp.hotreload.tasks.DaemonStopTask
import com.solufee.cmp.hotreload.tasks.HotReloadTask
import com.solufee.cmp.hotreload.tasks.PackageTask
import com.solufee.cmp.hotreload.tasks.ReloadTask
import com.solufee.cmp.hotreload.tasks.RunTask
import com.solufee.cmp.hotreload.tasks.VerifyReleaseTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * com.solufee.cmp.hotreload — apply to the root project.
 *
 * Adds tasks in group "hot reload" and nothing else: no existing task, configuration or variant is touched,
 * so release builds are unaffected.
 */
class HotReloadPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project != project.rootProject) {
            throw GradleException("Apply com.solufee.cmp.hotreload to the root project and declare modules with app(\":x\") / library(\":y\").")
        }
        val ext = project.extensions.create("cmpHotReload", HotReloadExtension::class.java)
        val providers = project.providers
        val rootDir = project.rootDir
        val workDir = File(rootDir, "build/hotreload")

        // Every cmp.hotreload.* setting: -P / gradle.properties first, then the git-ignored local.properties.
        val localPropsText: Provider<String> =
            providers.fileContents(project.layout.projectDirectory.file("local.properties")).asText
        fun prop(name: String): Provider<String> = providers.gradleProperty(name).orElse(
            localPropsText.map { text -> Properties().apply { load(text.reader()) }.getProperty(name) ?: "" }.filter { it.isNotBlank() },
        )
        fun home(path: String) = File(path.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home"))).absolutePath

        ext.enabled.convention(prop("cmp.hotreload.enabled").map { it.toBoolean() }.orElse(true))
        ext.useDaemon.convention(prop("cmp.hotreload.useDaemon").map { it.toBoolean() }.orElse(true))
        ext.keepCompactUnwind.convention(prop("cmp.hotreload.keepCompactUnwind").map { it.toBoolean() }.orElse(true))
        ext.warmRestart.convention(prop("cmp.hotreload.warmRestart").map { it.toBoolean() }.orElse(true))
        // No fixed default here: see resolveBinaries (toolchain download layout first, then tools/).
        ext.orcRuntime.convention(project.layout.file(prop("cmp.hotreload.orcRuntime").map { File(home(it)) }))
        ext.objcUtilsLib.convention(project.layout.file(prop("cmp.hotreload.objcUtilsLib").map { File(home(it)) }))
        ext.llvmHome.convention(project.layout.dir(prop("cmp.hotreload.llvmHome").map { File(home(it)) }))
        ext.simulator.udid.convention(prop("cmp.hotreload.simulatorUdid").orElse("booted"))
        ext.simulator.port.convention(prop("cmp.hotreload.port").map { it.toInt() })
        ext.simulator.launchLog.convention(project.layout.projectDirectory.file("launch.log"))
        ext.simulator.launchTimeoutSeconds.convention(prop("cmp.hotreload.launchTimeoutSeconds").map { it.toInt() }.orElse(120))
        ext.simulator.launchRetries.convention(prop("cmp.hotreload.launchRetries").map { it.toInt() }.orElse(3))
        ext.simulator.readyMarker.convention(prop("cmp.hotreload.readyMarker"))
        ext.simulator.reinstall.convention(prop("cmp.hotreload.reinstall").map { it.toBoolean() }.orElse(true))
        ext.modules.configureEach {
            sourceDir.convention("src/main/kotlin")
            infoPlist.convention("Info.plist")
            linkerOptions.convention(DEFAULT_LINKER_OPTIONS)
        }

        val toolchainDirPath: Provider<String> = ext.toolchainDir.map { it.asFile.absolutePath }
            .orElse(prop("cmp.hotreload.toolchainDir"))
            .orElse(providers.environmentVariable("CMP_HOT_RELOAD_TOOLCHAIN"))
            .map { home(it) }

        // Parsed through a provider (configuration-cache safe). A missing or bad manifest yields an empty map;
        // hotReloadCheckToolchain then fails with the explanation before any other task runs.
        val manifest: Provider<Map<String, String>> = toolchainDirPath
            .flatMap { dir -> providers.fileContents(project.layout.projectDirectory.file(File(dir, TOOLCHAIN_MANIFEST).path)).asText }
            .map { text -> manifestFields(text) }
            .orElse(emptyMap())

        val javaHome: Provider<String> = ext.daemonJavaHome.map { it.asFile.absolutePath }
            .orElse(providers.environmentVariable("JAVA_HOME"))
            .orElse(providers.systemProperty("java.home"))

        val check = project.tasks.register("hotReloadCheckToolchain", CheckToolchainTask::class.java) {
            group = GROUP
            description = "Check toolchain.json and the host against the toolchain this plugin is pinned to"
            toolchainDir.set(toolchainDirPath)
            probeHost.set(prop("cmp.hotreload.probeHost").map { it.toBoolean() }.orElse(true))
            cacheFile.set(File(workDir, "toolchain-check.cache").path)
        }

        val enabled = ext.enabled
        project.tasks.withType(HotReloadTask::class.java).configureEach {
            if (this !is DaemonStopTask && this !is CleanTask) dependsOn(check)
            onlyIf("cmpHotReload.enabled is true") { enabled.get() }
        }
        project.tasks.withType(CheckToolchainTask::class.java).configureEach {
            onlyIf("cmpHotReload.enabled is true") { enabled.get() }
        }

        project.afterEvaluate {
            val errors = mutableListOf<String>()
            val modules = resolveModules(project, ext, errors)
            val libraries = modules.filter { it.kind == ModuleKind.LIBRARY }
            val app = modules.firstOrNull { it.kind == ModuleKind.APP }
            val appSpec = app?.let { a -> ext.modules.findByName(a.path) }
            val appDir = app?.let { project.findProject(it.path)?.projectDir }
            val infoPlist = appDir?.let { File(it, appSpec?.infoPlist?.get() ?: "Info.plist").path } ?: ""
            val linkerOptions = appSpec?.linkerOptions?.get() ?: DEFAULT_LINKER_OPTIONS
            val socketDir = socketDir(rootDir)
            val tc = manifest.getOrElse(emptyMap())
            val bins = resolveBinaries(
                dist = toolchainDirPath.orNull,
                manifestTools = listOf(tc["toolsOrcRuntime"], tc["toolsUikitUtils"], tc["toolsLlvm"]),
                orcRuntime = ext.orcRuntime.orNull?.asFile,
                objcUtilsLib = ext.objcUtilsLib.orNull?.asFile,
                llvmHome = ext.llvmHome.orNull?.asFile,
                projectDir = rootDir,
            )
            val requiredFiles = linkedMapOf(
                "cmpHotReload.orcRuntime (liborc_rt_iossim_arm64.a)" to bins.orcRuntime,
                "cmpHotReload.objcUtilsLib (libCMPUIKitObjcUtils.a)" to bins.objcUtilsLib,
            )
            if (app != null) requiredFiles["app Info.plist"] = infoPlist
            modules.forEach { requiredFiles["${it.path} sources"] = it.srcDir }

            check.configure {
                configErrors.set(errors.toList())
                this.requiredFiles.set(requiredFiles)
            }

            val settings: Provider<HrSettings> = manifest.map { tc ->
                HrSettings(
                    rootDir = rootDir.path,
                    workDir = workDir.path,
                    dist = tc["dist"] ?: "",
                    kotlinc = tc["kotlincNative"] ?: "",
                    compilerJar = tc["compilerJar"] ?: "",
                    composePlugin = tc["pluginJar"] ?: "",
                    klibList = tc["klibList"] ?: "",
                    kaldoDylib = tc["kaldoIossim"] ?: "",
                    kaldoSha256 = tc["kaldoIossimSha256"],
                    target = tc["target"] ?: "ios_simulator_arm64",
                    orcRuntime = bins.orcRuntime,
                    objcUtilsLib = bins.objcUtilsLib,
                    llvmHome = bins.llvmHome,
                    useDaemon = ext.useDaemon.get(),
                    socketDir = socketDir.path,
                    javaHome = javaHome.get(),
                    modules = modules,
                    infoPlist = infoPlist,
                    linkerOptions = linkerOptions,
                    keepCompactUnwind = ext.keepCompactUnwind.get(),
                    port = ext.simulator.port.orNull ?: tc["reloadPort"]?.toIntOrNull() ?: 5567,
                    udid = ext.simulator.udid.get(),
                    launchLog = ext.simulator.launchLog.get().asFile.path,
                    readyMarker = ext.simulator.readyMarker.orNull,
                    launchTimeoutSec = ext.simulator.launchTimeoutSeconds.get(),
                    launchRetries = ext.simulator.launchRetries.get(),
                    reinstall = ext.simulator.reinstall.get(),
                    warmRestart = ext.warmRestart.get(),
                )
            }
            val toolchainKey: Provider<String> = manifest.map { "${it["compilerJar"]}|${it["pluginJar"]}|${it["klibList"]}" }

            val klibTasks = libraries.associate { m ->
                m.path to project.tasks.register("hotReloadCompileKlib" + taskSuffix(m), CompileKlibTask::class.java) {
                    group = GROUP
                    description = "Compile ${m.path} to a klib"
                    this.settings.set(settings)
                    modulePath.set(m.path)
                    sourceDir.set(File(m.srcDir))
                    this.toolchainKey.set(toolchainKey)
                    klib.set(File(workDir, "klib/${m.label}/${m.label}.klib"))
                }
            }
            // Each klib task consumes the klibs of its dependencies (this also orders them).
            for (m in libraries) {
                klibTasks.getValue(m.path).configure {
                    m.deps.forEach { d -> klibTasks[d]?.let { dep -> dependencyKlibs.from(dep.flatMap { it.klib }) } }
                }
            }
            val allKlibs = project.tasks.register("hotReloadCompileKlibs") {
                group = GROUP
                description = "Compile every library module to a klib"
                dependsOn(klibTasks.values)
            }
            project.tasks.register("hotReloadPackage", PackageTask::class.java) {
                group = GROUP
                description = "Build the hot-reloadable simulator app (split compile, merged payloads, .app)"
                this.settings.set(settings)
                dependsOn(allKlibs)
            }
            project.tasks.register("hotReload", ReloadTask::class.java) {
                group = GROUP
                description = "Classify the change (INTERFACE §10); hot: compile what changed, merge, check symbols and send it; " +
                    "warm: rebuild and relaunch with the saved state (--no-warm: only report)"
                this.settings.set(settings)
                dependsOn(allKlibs)
            }
            project.tasks.register("hotReloadRun", RunTask::class.java) {
                group = GROUP
                description = "Install and launch the app on the simulator; returns once it is up"
                this.settings.set(settings)
            }
            project.tasks.register("hotReloadVerifyRelease", VerifyReleaseTask::class.java) {
                group = GROUP
                description = "Build a release binary (no hot reload) and compare section sizes, symbols and __text with the baseline"
                this.settings.set(settings)
                recordBaseline.set(prop("cmp.hotreload.releaseBaseline").map { it.toBoolean() }.orElse(false))
                maxBaselineRuns.set(prop("cmp.hotreload.releaseBaselineRuns").map { it.toInt() }.orElse(6))
            }
            project.tasks.register("hotReloadDaemonStart", DaemonStartTask::class.java) {
                group = GROUP
                description = "Start the compile daemons, or reuse them when they answer"
                this.settings.set(settings)
            }
            project.tasks.register("hotReloadClean", CleanTask::class.java) {
                group = GROUP
                description = "Stop this project's compile daemons and delete build/hotreload (including the merge cache)"
                this.settings.set(settings)
            }
            project.tasks.register("hotReloadDaemonStop", DaemonStopTask::class.java) {
                group = GROUP
                description = "Stop this project's compile daemons"
                this.settings.set(settings)
            }
        }
    }

    companion object {
        const val GROUP = "hot reload"
        val DEFAULT_LINKER_OPTIONS = listOf("-framework", "ImageIO", "-Wl,-no_warn_duplicate_libraries")

        /** ":feature:ui" -> "FeatureUi". */
        fun taskSuffix(m: HrModule) = m.label.split('-', '_', '.').joinToString("") { it.replaceFirstChar(Char::uppercase) }

        /**
         * Declared modules, or — when none are declared — every subproject with src/main/kotlin: the one with
         * src/main/kotlin/main.kt is the app, the others are libraries in path order. When only the app is
         * declared, the other source modules become its libraries.
         */
        fun resolveModules(project: Project, ext: HotReloadExtension, errors: MutableList<String>): List<HrModule> {
            data class Decl(val path: String, val kind: ModuleKind, val sourceDir: String, val dependsOn: List<String>?)
            val declared = ext.modules.map { m ->
                Decl(m.name, m.kind.orNull ?: ModuleKind.LIBRARY, m.sourceDir.get(), m.dependsOn.orNull?.takeIf { it.isNotEmpty() })
            }
            val discovered = project.subprojects.filter { File(it.projectDir, "src/main/kotlin").isDirectory }.sortedBy { it.path }
            val decls = when {
                declared.isEmpty() -> {
                    val app = discovered.filter { File(it.projectDir, "src/main/kotlin/main.kt").isFile }
                    if (app.size != 1) {
                        errors += "cannot discover the app module: expected exactly one subproject with src/main/kotlin/main.kt, " +
                            "found ${app.map { it.path }}; declare app(\":x\")"
                    }
                    discovered.map { p -> Decl(p.path, if (p == app.firstOrNull()) ModuleKind.APP else ModuleKind.LIBRARY, "src/main/kotlin", null) }
                }
                declared.none { it.kind == ModuleKind.LIBRARY } -> declared + discovered.filter { p -> declared.none { it.path == p.path } }
                    .map { Decl(it.path, ModuleKind.LIBRARY, "src/main/kotlin", null) }
                else -> declared
            }
            val apps = decls.filter { it.kind == ModuleKind.APP }
            if (apps.size != 1) errors += "exactly one app(...) module is required; found ${apps.map { it.path }}"
            val libs = decls.filter { it.kind == ModuleKind.LIBRARY }
            val result = mutableListOf<HrModule>()
            for (d in decls) {
                val dir = project.findProject(d.path)?.projectDir
                if (dir == null) {
                    errors += "module ${d.path} is declared in cmpHotReload but is not included in settings.gradle(.kts)"
                    continue
                }
                val before = libs.takeWhile { it.path != d.path }.map { it.path }
                val deps = d.dependsOn ?: (if (d.kind == ModuleKind.APP) libs.map { it.path } else before)
                deps.filter { dep -> libs.none { it.path == dep } }.forEach { errors += "${d.path} depends on $it, which is not a declared library" }
                if (d.kind == ModuleKind.LIBRARY) {
                    deps.filter { it !in before && libs.any { l -> l.path == it } }.forEach {
                        errors += "${d.path} depends on $it, which is declared after it; declare libraries in dependency order"
                    }
                }
                result += HrModule(d.path, d.kind, File(dir, d.sourceDir).path, deps.filter { dep -> libs.any { it.path == dep } })
            }
            // App first, then libraries in declaration order.
            return result.sortedBy { if (it.kind == ModuleKind.APP) 0 else 1 }
        }

        data class Binaries(val orcRuntime: String, val objcUtilsLib: String, val llvmHome: String?)

        /**
         * The ORC runtime, libCMPUIKitObjcUtils.a and the LLVM tools. Order: explicit setting (DSL or
         * cmp.hotreload.* property) → `tools` of toolchain.json (a downloaded toolchain, INTERFACE §9.4) → the
         * layout fetch-toolchain.py unpacks next to the dist (`<dist>/../orc-runtime`, `../cmp-uikit-utils`,
         * `../llvm-tools`) → `tools/` in the project. With a downloaded toolchain the user sets only toolchainDir.
         * [manifestTools] = orcRuntime, uikitUtils, llvmTools from the manifest (null when absent).
         */
        fun resolveBinaries(
            dist: String?, orcRuntime: File?, objcUtilsLib: File?, llvmHome: File?, projectDir: File,
            manifestTools: List<String?> = listOf(null, null, null),
        ): Binaries {
            val root = dist?.let { File(it).absoluteFile.parentFile }
            fun fetched(rel: String) = root?.let { File(it, rel) }?.takeIf { it.exists() }
            fun listed(i: Int) = manifestTools.getOrNull(i)?.let { File(it) }
            return Binaries(
                orcRuntime = (orcRuntime ?: listed(0) ?: fetched("orc-runtime/liborc_rt_iossim_arm64.a") ?: File(projectDir, "tools/liborc_rt_iossim_arm64.a")).path,
                objcUtilsLib = (objcUtilsLib ?: listed(1) ?: fetched("cmp-uikit-utils/libCMPUIKitObjcUtils.a") ?: File(projectDir, "tools/libCMPUIKitObjcUtils.a")).path,
                llvmHome = (llvmHome ?: listed(2) ?: fetched("llvm-tools")?.takeIf { File(it, "bin/llvm-objcopy").isFile })?.path,
            )
        }

        /** macOS limits a Unix socket path to 104 bytes; deep project paths fall back to a private cache dir. */
        private const val MAX_SOCKET_PATH = 100

        fun socketDir(rootDir: File): File {
            val inProject = File(rootDir, ".hr")
            if (File(inProject, "hr-compile.sock").path.toByteArray().size <= MAX_SOCKET_PATH) return inProject
            val hash = MessageDigest.getInstance("SHA-1").digest(rootDir.absolutePath.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(12)
            return File(System.getProperty("user.home"), "Library/Caches/cmp-hot-reload/$hash")
        }

        /** The toolchain.json fields the pipeline uses (docs/INTERFACE.md §1). */
        fun manifestFields(text: String): Map<String, String> = try {
            val t = Toolchain.parse(File(TOOLCHAIN_MANIFEST), text)
            buildMap {
                t.kotlinDist?.let { put("dist", it) }
                t.kotlincNative?.let { put("kotlincNative", it) }
                t.compilerJar?.let { put("compilerJar", it) }
                t.composePluginJar?.let { put("pluginJar", it) }
                t.klibList?.let { put("klibList", it) }
                t.kaldoIossim?.let { put("kaldoIossim", it) }
                t.kaldoIossimSha256?.let { put("kaldoIossimSha256", it) }
                t.target?.let { put("target", it) }
                t.reloadPort?.let { put("reloadPort", it.toString()) }
                t.toolsLlvm?.let { put("toolsLlvm", it) }
                t.toolsOrcRuntime?.let { put("toolsOrcRuntime", it) }
                t.toolsUikitUtils?.let { put("toolsUikitUtils", it) }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
