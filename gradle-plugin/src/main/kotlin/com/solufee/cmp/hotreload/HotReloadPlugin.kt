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
import com.solufee.cmp.hotreload.tasks.WatchAnnounce
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * com.solufee.cmp.hotreload - apply to the root project (it then covers the subprojects), or, since 0.2.0, to
 * the app module itself (a Kotlin Multiplatform module such as the KMP template's `shared`/`composeApp`).
 *
 * Adds tasks in group "hot reload" and nothing else: no existing task, configuration or variant is touched,
 * so release builds are unaffected. The Kotlin Multiplatform source sets and dependencies are only read.
 */
class HotReloadPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val singleModule = project != project.rootProject
        val ext = project.extensions.create("cmpHotReload", HotReloadExtension::class.java)
        val providers = project.providers
        val rootDir = project.projectDir
        val workDir = File(rootDir, "build/hotreload")

        // Every cmp.hotreload.* setting: -P / gradle.properties first, then the git-ignored local.properties
        // of the root project (where the KMP template keeps it).
        val localPropsText: Provider<String> =
            providers.fileContents(project.rootProject.layout.projectDirectory.file("local.properties")).asText
        fun prop(name: String): Provider<String> = providers.gradleProperty(name).orElse(
            localPropsText.map { text -> Properties().apply { load(text.reader()) }.getProperty(name) ?: "" }.filter { it.isNotBlank() },
        )
        fun home(path: String) = File(path.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home"))).absolutePath

        ext.enabled.convention(prop("cmp.hotreload.enabled").map { it.toBoolean() }.orElse(true))
        ext.useDaemon.convention(prop("cmp.hotreload.useDaemon").map { it.toBoolean() }.orElse(true))
        ext.keepCompactUnwind.convention(prop("cmp.hotreload.keepCompactUnwind").map { it.toBoolean() }.orElse(true))
        ext.warmRestart.convention(prop("cmp.hotreload.warmRestart").map { it.toBoolean() }.orElse(true))
        ext.forceRecompose.convention(prop("cmp.hotreload.forceRecompose").map { it.toBoolean() }.orElse(true))
        ext.mainWaitMs.convention(prop("cmp.hotreload.mainWaitMs").map { it.trim().toInt() }.orElse(2000))
        ext.stableKeys.convention(prop("cmp.hotreload.stableKeys").map { it.toBoolean() }.orElse(true))
        ext.libsInExecutable.convention(prop("cmp.hotreload.libsInExecutable").map { it.toBoolean() }.orElse(false))
        ext.deviceFamily.convention(prop("cmp.hotreload.deviceFamily").map { v -> v.split(',').mapNotNull { it.trim().toIntOrNull() } })
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
        ext.resolveDependencyKlibs.convention(prop("cmp.hotreload.resolveDependencyKlibs").map { it.toBoolean() }.orElse(true))
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

        // After every project is configured, so the Kotlin Multiplatform source sets and dependencies of the
        // subprojects are complete (without forcing an evaluation order on the user's build).
        project.gradle.projectsEvaluated {
            val errors = mutableListOf<String>()
            val kmp = KmpModules()
            val included = includedModules(project, ext, errors)
            val modules = withIncluded(resolveModules(project, ext, errors, singleModule, kmp), included.map { it.module })
            val libraries = modules.filter { it.kind == ModuleKind.LIBRARY }
            val app = modules.firstOrNull { it.kind == ModuleKind.APP }
            val appSpec = app?.let { a -> ext.modules.findByName(a.path) }
            val appDir = app?.let { project.findProject(it.path)?.projectDir }
            val declaredPlist = appDir?.let { File(it, appSpec?.infoPlist?.get() ?: "Info.plist") }
            val iosEntry = appSpec?.iosEntry?.orNull ?: app?.let { a -> detectEntry(project, a.sourceDirs.map(::File)) }
            // No Info.plist in the app module (the KMP template keeps it in the Xcode project): generate one.
            val generatedPlist = File(workDir, "generated/Info.plist")
            val appProject = app?.let { project.findProject(it.path) }
            val infoPlist = when {
                declaredPlist == null -> ""
                declaredPlist.isFile -> declaredPlist.path
                else -> generatedPlist.path
            }
            val plistContent = if (declaredPlist != null && !declaredPlist.isFile) {
                val exe = (appProject?.rootProject?.name ?: "App").filter { it.isLetterOrDigit() }.ifEmpty { "App" }
                val bundleId = appSpec?.bundleId?.orNull ?: iosEntry?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() }?.let { "$it.hotreload" }
                    ?: "com.example.${exe.lowercase()}.hotreload"
                val family = ext.deviceFamily.orNull?.takeIf { it.isNotEmpty() } ?: listOf(1)
                if (!Kmp.DEVICE_FAMILIES.containsAll(family)) {
                    errors += "deviceFamily = $family: only 1 (iPhone) and 2 (iPad) are allowed, e.g. listOf(1, 2)"
                }
                Kmp.infoPlist(exe, bundleId, family.distinct().sorted())
            } else {
                if (declaredPlist != null && ext.deviceFamily.orNull?.isNotEmpty() == true) {
                    project.logger.warn("CMP hot reload: deviceFamily is ignored: the app module has its own ${declaredPlist.path}, which is used as is " +
                        "(set UIDeviceFamily there)")
                }
                null
            }
            // Compose Multiplatform resources assembled for iosSimulatorArm64, of the app and of every library module
            // that has them (a library's strings and drawables are looked up in the same bundle directory).
            val composeResProjects = modules.mapNotNull { m -> project.findProject(m.path) }
                .mapNotNull { p -> p.tasks.findByName("assembleIosSimulatorArm64MainResources")?.let { p to it } }
            // Modules of included builds that are linked as their build's klibs (not declared): their Compose resources
            // are assembled by their build and bundled too (which of them the app uses is known only when resolving).
            val foreignRes = foreignResourceModules(project, included.map { it.componentKey }.toSet())
            val composeResTasks: List<Any> = composeResProjects.map { it.second } + included.flatMap { it.generatorTasks } + foreignRes.map { it.first }
            val composeResources = composeResProjects.map { (p, _) ->
                File(p.layout.buildDirectory.get().asFile, "generated/compose/resourceGenerator/assembledResources/iosSimulatorArm64Main").path
            } + included.mapNotNull { it.composeResources } + foreignRes.map { it.second }
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
            if (app != null && plistContent == null) requiredFiles["app Info.plist"] = infoPlist
            // Sources: the module directory; generated KMP source dirs only exist once their tasks ran.
            modules.forEach { requiredFiles["${it.path} sources"] = if (it.path in kmp.dirSets) project.findProject(it.path)!!.projectDir.path else it.srcDir }

            // #10: Kotlin source sets of included / DSL-declared modules that hot reload does not compile.
            val sourceSetWarnings = included.mapNotNull { inc ->
                uncollectedSourceSets(inc.dir, inc.module.sourceDirs).takeIf { it.isNotEmpty() }?.let { missing ->
                    sourceSetWarning(inc.module.path.trim(':'),
                        "includedModule(\"${inc.componentKey.substringBefore('|')}\", \"${inc.componentKey.substringAfter('|')}\")", inc.dir, inc.module.fragments, missing)
                }
            } + modules.filter { m -> m.path !in kmp.dirSets && included.none { it.module.path == m.path } && m.fragments.size > 1 }.mapNotNull { m ->
                val dir = project.findProject(m.path)?.projectDir ?: return@mapNotNull null
                uncollectedSourceSets(dir, m.sourceDirs).takeIf { it.isNotEmpty() }?.let { missing ->
                    sourceSetWarning(m.path, (if (m.kind == ModuleKind.APP) "app" else "library") + "(\"${m.path}\")", dir, m.fragments, missing)
                }
            }
            check.configure {
                configWarnings.set(sourceSetWarnings)
                configErrors.set(errors.toList())
                this.requiredFiles.set(requiredFiles)
            }

            val settings: Provider<HrSettings> = manifest.map { tc ->
                HrSettings(
                    rootDir = rootDir.path,
                    sourceSetWarnings = sourceSetWarnings,
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
                    toolchainReadyMarker = tc["readyMarker"],
                    launchTimeoutSec = ext.simulator.launchTimeoutSeconds.get(),
                    launchRetries = ext.simulator.launchRetries.get(),
                    reinstall = ext.simulator.reinstall.get(),
                    warmRestart = ext.warmRestart.get(),
                    forceRecompose = ext.forceRecompose.get(),
                    mainWaitMs = ext.mainWaitMs.get(),
                    stableKeys = ext.stableKeys.get(),
                    libsInExecutable = ext.libsInExecutable.get(),
                    iosEntry = iosEntry,
                    composeResources = composeResources,
                    infoPlistContent = plistContent,
                )
            }
            // Dependency klibs of every module (lazy: resolved only when a hot-reload task runs).
            val depKlibs = project.files()
            val depIds = mutableListOf<Provider<List<String>>>()
            val depVia = mutableListOf<Provider<List<String>>>()
            // With DSL errors (e.g. an includedModule naming a build that is not included) nothing is resolved or
            // compiled: hotReloadCheckToolchain reports them first.
            if (ext.resolveDependencyKlibs.get() && errors.isEmpty()) {
                for (m in modules) {
                    val cfg = project.findProject(m.path)?.configurations?.findByName(Kmp.DEPENDENCY_CONFIGURATION) ?: continue
                    // Skip this build's projects (hot reload modules) and the declared included modules (compiled from
                    // source); a project of another build (composite build) that is not declared is linked as its klib.
                    val thisBuild = buildPath(project)
                    val fromSource = included.map { it.componentKey }.toSet()
                    val view = cfg.incoming.artifactView {
                        componentFilter { id ->
                            id !is org.gradle.api.artifacts.component.ProjectComponentIdentifier ||
                                (id.build.buildPath != thisBuild && "${id.build.buildPath.substringAfterLast(':')}|${id.projectPath}" !in fromSource)
                        }
                    }
                    depKlibs.from(view.files)
                    depIds += view.artifacts.resolvedArtifacts.map { set -> set.map { "${it.id.componentIdentifier.displayName}|${it.file.path}" } }
                    depVia += cfg.incoming.resolutionResult.rootComponent.map { Kmp.requesters(it) }
                }
            }
            depKlibs.from(ext.extraKlibs)
            val extraIds: Provider<List<String>> = project.provider { ext.extraKlibs.files.map { "${it.name}|${it.path}" } }
            val allIds: Provider<List<String>> = depIds.fold(extraIds) { acc, p -> acc.zip(p) { a, b -> a + b } }
            // #8: what hotReload / hotReloadWatch watch - Kotlin sources of every fragment (generated code excluded: it
            // follows the generator tasks) and src/<set>/composeResources of every hot module.
            val generated = "${File.separator}build${File.separator}"
            val moduleDirs = modules.mapNotNull { m -> project.findProject(m.path)?.projectDir } + included.map { it.dir }
            val watched = (modules.flatMap { it.sourceDirs }.filter { generated !in it } +
                moduleDirs.flatMap { d -> File(d, "src").listFiles()?.map { File(it, "composeResources").path } ?: emptyList() }).distinct()
            val continuousBuild = project.gradle.startParameter.isContinuous
            project.tasks.withType(ReloadTask::class.java).configureEach {
                watchedSources.from(watched)
                continuous.set(continuousBuild)
            }
            project.tasks.withType(HotReloadTask::class.java).configureEach {
                if (this !is DaemonStopTask && this !is CleanTask) {
                    externalKlibFiles.from(depKlibs)
                    externalKlibIds.set(allIds)
                    fromSourceModules.set(included.map { it.fromSource })
                    externalKlibVia.set(depVia.fold(project.provider { emptyList<String>() }) { acc, p -> acc.zip(p) { a, b -> a + b } })
                    composeVersion.set(PinnedToolchain.current.composeVersion)
                    // Generated KMP sources (Compose resource accessors, ...) are built before any compile.
                    kmp.dirSets.values.flatten().forEach { dependsOn(it) }
                    composeResTasks.forEach { dependsOn(it) }
                }
            }
            val toolchainKey: Provider<String> = manifest.map { "${it["compilerJar"]}|${it["pluginJar"]}|${it["klibList"]}" }

            val klibTasks = libraries.associate { m ->
                m.path to project.tasks.register("hotReloadCompileKlib" + taskSuffix(m), CompileKlibTask::class.java) {
                    group = GROUP
                    description = "Compile ${m.path} to a klib"
                    this.settings.set(settings)
                    modulePath.set(m.path)
                    sources.from(m.sourceDirs)
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
            project.tasks.register("hotReloadWatch", ReloadTask::class.java) {
                group = GROUP
                description = "With -t (./gradlew hotReloadWatch -t): a hot reload on every saved change of the hot modules' sources and resources"
                this.settings.set(settings)
                dependsOn(allKlibs)
                doFirst(WatchAnnounce())
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
                maxBaselineRuns.set(prop("cmp.hotreload.releaseBaselineRuns").map { it.toInt() }.orElse(1))
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
         * [Kmp.detectIosEntry] through a ValueSource: the configuration cache then keys on the detected entry, not on
         * the contents of every source file it read, so editing sources keeps the cache (hotReloadWatch -t, #8).
         */
        fun detectEntry(p: Project, dirs: List<File>): String? =
            p.providers.of(IosEntrySource::class.java) { parameters.dirs.set(dirs.map { it.path }) }.orNull

        /** Identity path of the build [p] belongs to (":" for the root build, ":lib-build" for an included build). */
        internal fun buildPath(p: Project): String =
            runCatching { p.gradle.javaClass.getMethod("getIdentityPath").invoke(p.gradle).toString() }.getOrNull()
                ?: if (p.gradle.parent == null) ":" else ":${p.rootProject.name}"

        /** Source sets of other targets (and other iOS architectures): never part of the iosSimulatorArm64 compilation. */
        private val OTHER_TARGETS = Regex("^(android|jvm|js|wasm|desktop|linux|mingw|macos|tvos|watchos|iosArm64|iosX64)")

        /**
         * src/<name>Main/kotlin directories of a module (with Kotlin files) that are not among [collected]: Kotlin source
         * sets hot reload would not compile. Other targets' source sets are left out. Only directories are listed (no
         * file is read), so this does not tie the configuration cache to source contents.
         */
        fun uncollectedSourceSets(dir: File, collected: Collection<String>): List<File> =
            (File(dir, "src").listFiles() ?: emptyArray()).filter { it.isDirectory && it.name.endsWith("Main") && !OTHER_TARGETS.containsMatchIn(it.name) }
                .map { File(it, "kotlin") }
                .filter { k -> k.isDirectory && k.path !in collected && k.walkTopDown().any { it.isFile && it.extension == "kt" } }
                .sortedBy { it.path }

        /** The warning for [missing] source sets of a module, with the source set declaration to add. */
        fun sourceSetWarning(label: String, declare: String, dir: File, fragments: List<HrFragment>, missing: List<File>): String {
            val src = File(dir, "src").path + File.separator
            val present = fragments.mapNotNull { f -> f.dirs.firstOrNull { it.startsWith(src) && File(it).isDirectory }?.let { f.name to it.removePrefix(dir.path + File.separator) } }
            val lines = present.mapIndexed { i, (name, path) ->
                "create(\"$name\") { srcDir(\"$path\")" + (if (i == 0) "" else "; dependsOn(\"${present[i - 1].first}\")") + " }"
            } + missing.map { k ->
                val name = k.parentFile.name
                "create(\"$name\") { srcDir(\"src/$name/kotlin\"); dependsOn(\"${present.firstOrNull()?.first ?: "commonMain"}\") }   " +
                    "// adjust: as in the module's build script, and add \"$name\" to dependsOn of the source sets that use it"
            }
            return "CMP hot reload: warning: $label has Kotlin source sets that hot reload does not compile: ${missing.joinToString { it.path }}\n" +
                "  If they belong to the iOS compilation, declare the module's source sets (they replace the defaults):\n" +
                "  cmpHotReload {\n      $declare {\n          sourceSets {\n" + lines.joinToString("") { "              $it\n" } +
                "          }\n      }\n  }"
        }

        /** The KGP default hierarchy for iosSimulatorArm64, parents first. */
        val DEFAULT_HIERARCHY = listOf("commonMain", "nativeMain", "appleMain", "iosMain", "iosSimulatorArm64Main")

        /** Compose resource generators of a KMP module for iosSimulatorArm64 (Compose Multiplatform 1.12/1.13). */
        val COMPOSE_GENERATORS = listOf("generateComposeResClass", "generateExpectResourceCollectorsForCommonMain",
            "generateActualResourceCollectorsForIosSimulatorArm64Main") +
            DEFAULT_HIERARCHY.map { "generateResourceAccessorsFor" + it.replaceFirstChar(Char::uppercase) } +
            "assembleIosSimulatorArm64MainResources"

        /** A declared included module: the hot reload module, its generator task references and assembled resources. */
        class Included(val module: HrModule, val componentKey: String, val generatorTasks: List<Any>, val composeResources: String?,
                       val dir: File, val buildDir: File) {
            /** `build|path|module dir|included build dir`, for leaving its own build's klib out (no double linking). */
            val fromSource: String get() = "$componentKey|${dir.path}|${buildDir.path}"
        }

        /**
         * Fragments of an included module laid out by the default hierarchy: each source set's src/<set>/kotlin and the
         * Compose resource code generated for it under build/generated/compose/resourceGenerator/kotlin.
         */
        fun defaultFragments(dir: File): List<HrFragment> {
            val gen = File(dir, "build/generated/compose/resourceGenerator/kotlin")
            return DEFAULT_HIERARCHY.mapIndexed { i, set ->
                val dirs = listOf(File(dir, "src/$set/kotlin"), File(gen, "${set}ResourceAccessors"), File(gen, "${set}ResourceCollectors")) +
                    (if (set == "commonMain") listOf(File(gen, "commonResClass")) else emptyList())
                HrFragment(set, dirs.map { it.path }, if (i == 0) emptyList() else listOf(DEFAULT_HIERARCHY[i - 1]))
            }
        }

        /**
         * Modules of this build's included builds (not declared with includedModule) that have Compose resources: a task
         * reference to their assembleIosSimulatorArm64MainResources and its output directory. Modules are found as
         * directories (up to two levels) with a build script and src/<set>/composeResources/.
         */
        fun foreignResourceModules(project: Project, declared: Set<String>): List<Pair<Any, String>> =
            (project.gradle.includedBuilds + (project.gradle.parent?.includedBuilds ?: emptyList())).distinctBy { it.name }.flatMap { ib ->
                val root = ib.projectDir
                root.walkTopDown().maxDepth(2).filter { d ->
                    d.isDirectory && d != root && (File(d, "build.gradle.kts").isFile || File(d, "build.gradle").isFile) &&
                        File(d, "src").listFiles()?.any { File(it, "composeResources").isDirectory } == true
                }.mapNotNull { d ->
                    val path = ":" + d.relativeTo(root).path.replace(File.separatorChar, ':')
                    if ("${ib.name}|$path" in declared) null
                    else ib.task("$path:assembleIosSimulatorArm64MainResources") to
                        File(d, "build/generated/compose/resourceGenerator/assembledResources/iosSimulatorArm64Main").path
                }.toList()
            }

        /** The declared included modules (0.2.2), resolved against this build's included builds. */
        fun includedModules(project: Project, ext: HotReloadExtension, errors: MutableList<String>): List<Included> {
            if (ext.includedModules.isEmpty()) return emptyList()
            // A plugin applied in an included build sees its siblings through the root build.
            val builds = (project.gradle.includedBuilds + (project.gradle.parent?.includedBuilds ?: emptyList())).associateBy { it.name }
            val specs = ext.includedModules.sortedBy { s -> ext.includedOrder.indexOf(s.name).let { if (it < 0) Int.MAX_VALUE else it } }
            return specs.mapNotNull { spec ->
                val buildName = spec.build.get()
                val path = spec.projectPath.get()
                val ib = builds[buildName]
                if (ib == null) {
                    errors += "includedModule(\"$buildName\", \"$path\"): no included build named $buildName " +
                        "(included builds: ${builds.keys.sorted().ifEmpty { listOf("none") }}); add includeBuild(...) to settings.gradle(.kts)"
                    return@mapNotNull null
                }
                val dir = File(ib.projectDir, spec.projectDir.orNull ?: path.trim(':').replace(':', '/'))
                if (!dir.isDirectory) {
                    val known = ib.projectDir.walkTopDown().maxDepth(2).filter { d ->
                        d != ib.projectDir && d.isDirectory && (File(d, "build.gradle.kts").isFile || File(d, "build.gradle").isFile)
                    }.map { ":" + it.relativeTo(ib.projectDir).path.replace(File.separatorChar, ':') }.sorted().toList()
                    errors += "includedModule(\"$buildName\", \"$path\"): no directory ${dir.path} in the included build $buildName " +
                        "(modules found there: ${known.ifEmpty { listOf("none") }}); fix the path or set projectDir = \"<dir in $buildName>\""
                    return@mapNotNull null
                }
                val fragments = if (spec.sourceSets.isEmpty()) defaultFragments(dir)
                else spec.sourceSets.map { HrFragment(it.name, it.srcDirs.get().map { d -> File(dir, d).path }, it.dependsOn.get()) }
                val hasResources = File(dir, "src").listFiles()?.any { File(it, "composeResources").isDirectory } == true
                val generators = spec.generatorTasks.orNull?.takeIf { it.isNotEmpty() }
                    ?: if (hasResources) COMPOSE_GENERATORS.map { "$path:$it" } else emptyList()
                val srcDir = fragments.firstNotNullOfOrNull { f -> f.dirs.firstOrNull { File(it).isDirectory } } ?: dir.path
                Included(
                    module = HrModule(spec.name, ModuleKind.LIBRARY, srcDir, emptyList(), fragments),
                    componentKey = "$buildName|$path",
                    generatorTasks = generators.map { ib.task(it) },
                    composeResources = if (hasResources) File(dir, "build/generated/compose/resourceGenerator/assembledResources/iosSimulatorArm64Main").path else null,
                    dir = dir, buildDir = ib.projectDir,
                )
            }
        }

        /**
         * Included modules go first (in declaration order: they cannot use this build's modules), then this build's
         * libraries; every library may use the libraries before it, the app uses all of them.
         */
        fun withIncluded(modules: List<HrModule>, included: List<HrModule>): List<HrModule> {
            if (included.isEmpty()) return modules
            val inc = included.mapIndexed { i, m -> m.copy(deps = included.take(i).map { it.path }) }
            val incPaths = inc.map { it.path }
            val rest = modules.map { m ->
                when (m.kind) {
                    ModuleKind.APP -> m.copy(deps = incPaths + m.deps.filter { it !in incPaths })
                    ModuleKind.LIBRARY -> m.copy(deps = incPaths + m.deps)
                }
            }
            return rest.filter { it.kind == ModuleKind.APP } + inc + rest.filter { it.kind == ModuleKind.LIBRARY }
        }

        /** Kotlin source directory sets of KMP modules (by project path): task inputs that carry generated sources. */
        class KmpModules {
            val dirSets = mutableMapOf<String, List<Any>>()
        }

        /**
         * Project dependencies of [p]'s iosSimulatorArm64 compilation (KMP), transitively, each after the projects
         * it depends on (so libraries come in dependency order); cycles are ignored.
         */
        internal fun projectDependencies(p: Project): List<Project> {
            val out = LinkedHashSet<Project>()
            fun direct(q: Project) = q.configurations.findByName(Kmp.DEPENDENCY_CONFIGURATION)?.allDependencies
                ?.withType(org.gradle.api.artifacts.ProjectDependency::class.java)
                ?.mapNotNull { q.findProject(it.path) }?.distinct() ?: emptyList()
            fun visit(q: Project, stack: Set<Project>) {
                for (d in direct(q)) {
                    if (d == p || d in stack || d in out) continue
                    visit(d, stack + d)
                    out += d
                }
            }
            visit(p, setOf(p))
            return out.toList()
        }

        /**
         * Declared modules, or - when none are declared - discovered ones:
         * - applied to the root: every subproject with src/main/kotlin or a Kotlin Multiplatform iosSimulatorArm64
         *   target; the app is the one with a Kotlin `main` (src/main/kotlin/main.kt) or a `MainViewController()`;
         * - applied to a module: that module is the app, its project dependencies are the libraries.
         * When only the app is declared, the other source modules become its libraries.
         * Sources of a module: its `sourceSets { }` in the DSL, else the Kotlin Multiplatform source sets that make
         * up its iosSimulatorArm64 compilation (commonMain -> ... -> iosSimulatorArm64Main), else [sourceDir].
         */
        fun resolveModules(
            project: Project, ext: HotReloadExtension, errors: MutableList<String>,
            singleModule: Boolean = false, kmp: KmpModules = KmpModules(),
        ): List<HrModule> {
            data class Decl(val path: String, val kind: ModuleKind, val sourceDir: String, val dependsOn: List<String>?, val sets: List<HrFragment>)
            val declared = ext.modules.map { m ->
                Decl(m.name, m.kind.orNull ?: ModuleKind.LIBRARY, m.sourceDir.get(), m.dependsOn.orNull?.takeIf { it.isNotEmpty() },
                    m.sourceSets.map { HrFragment(it.name, it.srcDirs.get(), it.dependsOn.get()) })
            }
            fun isKmp(p: Project) = Kmp.sourceSets(p) != null
            fun isApp(p: Project) = File(p.projectDir, "src/main/kotlin/main.kt").isFile ||
                (Kmp.sourceSets(p)?.let { ss -> detectEntry(p, ss.flatMap { it.dirs }) != null } ?: false)
            val candidates = if (singleModule) listOf(project) + projectDependencies(project) else project.subprojects.toList()
            val discovered = candidates.filter { File(it.projectDir, "src/main/kotlin").isDirectory || isKmp(it) }
                .let { if (singleModule) it else it.sortedBy { p -> p.path } }
            val decls = when {
                declared.isEmpty() && singleModule ->
                    listOf(Decl(project.path, ModuleKind.APP, "src/main/kotlin", null, emptyList())) +
                        discovered.filter { it != project }.map { Decl(it.path, ModuleKind.LIBRARY, "src/main/kotlin", null, emptyList()) }
                declared.isEmpty() -> {
                    val app = discovered.filter(::isApp)
                    if (app.size != 1) {
                        errors += "cannot discover the app module: expected exactly one subproject with src/main/kotlin/main.kt " +
                            "or a Kotlin Multiplatform MainViewController(), found ${app.map { it.path }}; declare app(\":x\")"
                    }
                    discovered.map { p -> Decl(p.path, if (p == app.firstOrNull()) ModuleKind.APP else ModuleKind.LIBRARY, "src/main/kotlin", null, emptyList()) }
                }
                declared.none { it.kind == ModuleKind.LIBRARY } -> declared + discovered.filter { p -> declared.none { it.path == p.path } }
                    .map { Decl(it.path, ModuleKind.LIBRARY, "src/main/kotlin", null, emptyList()) }
                else -> declared
            }
            val apps = decls.filter { it.kind == ModuleKind.APP }
            if (apps.size != 1) errors += "exactly one app(...) module is required; found ${apps.map { it.path }}"
            val libs = decls.filter { it.kind == ModuleKind.LIBRARY }
            val result = mutableListOf<HrModule>()
            for (d in decls) {
                val p = project.findProject(d.path)
                if (p == null) {
                    errors += "module ${d.path} is declared in cmpHotReload but is not included in settings.gradle(.kts)"
                    continue
                }
                val dir = p.projectDir
                val before = libs.takeWhile { it.path != d.path }.map { it.path }
                val deps = d.dependsOn ?: (if (d.kind == ModuleKind.APP) libs.map { it.path } else before)
                deps.filter { dep -> libs.none { it.path == dep } }.forEach { errors += "${d.path} depends on $it, which is not a declared library" }
                if (d.kind == ModuleKind.LIBRARY) {
                    deps.filter { it !in before && libs.any { l -> l.path == it } }.forEach {
                        errors += "${d.path} depends on $it, which is declared after it; declare libraries in dependency order"
                    }
                }
                val kgp = if (d.sets.isEmpty()) Kmp.sourceSets(p) else null
                val fragments = when {
                    d.sets.isNotEmpty() -> d.sets.map { f -> f.copy(dirs = f.dirs.map { File(dir, it).path }) }
                    kgp != null -> {
                        kmp.dirSets[d.path] = kgp.mapNotNull { it.directorySet }
                        kgp.map { HrFragment(it.name, it.dirs.map { f -> f.path }, it.dependsOn) }
                    }
                    else -> listOf(HrFragment("main", listOf(File(dir, d.sourceDir).path)))
                }
                val srcDir = fragments.firstNotNullOfOrNull { f -> f.dirs.firstOrNull { File(it).isDirectory } } ?: File(dir, d.sourceDir).path
                result += HrModule(d.path, d.kind, srcDir, deps.filter { dep -> libs.any { it.path == dep } }, fragments)
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
                t.readyMarker?.let { put("readyMarker", it) }
                t.toolsLlvm?.let { put("toolsLlvm", it) }
                t.toolsOrcRuntime?.let { put("toolsOrcRuntime", it) }
                t.toolsUikitUtils?.let { put("toolsUikitUtils", it) }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}

/** The KMP template's iOS entry (`fun MainViewController()`) of some source directories; see HotReloadPlugin.detectEntry. */
abstract class IosEntrySource : org.gradle.api.provider.ValueSource<String, IosEntrySource.Params> {
    interface Params : org.gradle.api.provider.ValueSourceParameters {
        val dirs: org.gradle.api.provider.ListProperty<String>
    }

    override fun obtain(): String? = Kmp.detectIosEntry(parameters.dirs.get().map(::File))
}
