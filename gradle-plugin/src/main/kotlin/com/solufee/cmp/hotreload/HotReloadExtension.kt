package com.solufee.cmp.hotreload

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

enum class ModuleKind { APP, LIBRARY }

/**
 * One source set of a module when the sources are declared by hand (projects without the Kotlin Multiplatform
 * plugin): directories relative to the module, and the source sets it depends on. Declare parents first.
 */
abstract class HotReloadSourceSet @Inject constructor(private val name: String) : Named {
    override fun getName(): String = name
    abstract val srcDirs: ListProperty<String>
    abstract val dependsOn: ListProperty<String>
    fun srcDir(path: String) = srcDirs.add(path)
    fun dependsOn(vararg sourceSets: String) = dependsOn.addAll(*sourceSets)
}

/**
 * A Kotlin Multiplatform module of an included build (composite build, `includeBuild(...)`) compiled from source
 * so that its code hot reloads too (0.2.2). Gradle does not show another build's project model to this build, so
 * the module is declared: the included build's name and the project path inside it. Its sources default to the
 * Kotlin Multiplatform default hierarchy for iosSimulatorArm64 (commonMain -> nativeMain -> appleMain -> iosMain ->
 * iosSimulatorArm64Main under src/<set>/kotlin) plus the Compose resource accessors that build generates.
 */
abstract class IncludedModuleSpec @Inject constructor(private val name: String, objects: ObjectFactory) : Named {
    override fun getName(): String = name
    abstract val build: Property<String>
    abstract val projectPath: Property<String>
    /** Module directory relative to the included build (default: the project path, ":a:b" -> "a/b"). */
    abstract val projectDir: Property<String>
    /** Source sets when the module does not use the default hierarchy (directories relative to the module). */
    val sourceSets: NamedDomainObjectContainer<HotReloadSourceSet> = objects.domainObjectContainer(HotReloadSourceSet::class.java)
    fun sourceSets(action: Action<in NamedDomainObjectContainer<HotReloadSourceSet>>) = action.execute(sourceSets)
    /**
     * Tasks of the module (paths inside its build, e.g. ":core:generateComposeResClass") run before compiling it.
     * Default: the Compose resource generators when the module has a composeResources/ directory.
     */
    abstract val generatorTasks: ListProperty<String>
}

/** One Gradle module that takes part in hot reload. The name is the project path, e.g. ":ui". */
abstract class HotReloadModule @Inject constructor(private val name: String, objects: ObjectFactory) : Named {
    override fun getName(): String = name

    /**
     * Source sets. Empty (default): read from `kotlin { sourceSets }` when the module applies Kotlin
     * Multiplatform (commonMain -> ... -> iosSimulatorArm64Main), otherwise the single [sourceDir].
     */
    val sourceSets: NamedDomainObjectContainer<HotReloadSourceSet> = objects.domainObjectContainer(HotReloadSourceSet::class.java)
    fun sourceSets(action: Action<in NamedDomainObjectContainer<HotReloadSourceSet>>) = action.execute(sourceSets)

    /**
     * App only: `package.Function` returning the root UIViewController, for apps without a Kotlin `main`
     * (the KMP template's `MainViewController()`); the plugin generates the UIKit entry around it.
     * Default: found automatically (a `fun MainViewController(` in the app's sources) when there is no main.
     */
    abstract val iosEntry: Property<String>

    /** App only: CFBundleIdentifier of the Info.plist the plugin generates when the module has none. */
    abstract val bundleId: Property<String>

    abstract val kind: Property<ModuleKind>

    /** Kotlin source directory, relative to the module directory. Default src/main/kotlin. */
    abstract val sourceDir: Property<String>

    /**
     * Libraries this module links against (project paths). Default: every library declared before it,
     * so a simple chain needs no configuration.
     */
    abstract val dependsOn: ListProperty<String>

    /** App only: Info.plist relative to the module directory. Default Info.plist. */
    abstract val infoPlist: Property<String>

    /** App only: extra linker options (each becomes -linker-option=...). */
    abstract val linkerOptions: ListProperty<String>
}

abstract class SimulatorSpec {
    /** Simulator UDID, or "booted" for the booted device. */
    abstract val udid: Property<String>

    /** Reload port (KONAN_HOT_RELOAD_PORT). Defaults to runtime.reloadPort from toolchain.json. */
    abstract val port: Property<Int>

    /** Console log of the running app. */
    abstract val launchLog: RegularFileProperty

    /**
     * A line the app prints once it is up (e.g. from its first frame). Without one, the app counts as
     * started when Kotlin main has been entered and the process stayed alive for 3 s.
     */
    abstract val readyMarker: Property<String>

    abstract val launchTimeoutSeconds: Property<Int>
    abstract val launchRetries: Property<Int>

    /** Uninstall and install before each launch (default true). */
    abstract val reinstall: Property<Boolean>
}

abstract class HotReloadExtension @Inject constructor(objects: ObjectFactory) {
    /**
     * Kotlin/Native dist that contains toolchain.json. When unset, the plugin reads the property
     * `cmp.hotreload.toolchainDir` (-P, gradle.properties, local.properties), then the environment
     * variable `CMP_HOT_RELOAD_TOOLCHAIN`.
     */
    abstract val toolchainDir: DirectoryProperty

    /** Debug switch. When false every hot-reload task is skipped; nothing else in the build changes. */
    abstract val enabled: Property<Boolean>

    /** Use the resident compile daemons (started, reused and health-checked by the plugin). */
    abstract val useDaemon: Property<Boolean>

    /** JDK that runs the compile daemons and kotlinc-native. Defaults to JAVA_HOME, else the JDK running Gradle. */
    abstract val daemonJavaHome: DirectoryProperty

    /** liborc_rt for the iOS simulator (until toolchain.json lists it). Default: tools/liborc_rt_iossim_arm64.a. */
    abstract val orcRuntime: RegularFileProperty

    /** libCMPUIKitObjcUtils.a from the Compose fork (until toolchain.json lists it). Default: tools/libCMPUIKitObjcUtils.a. */
    abstract val objcUtilsLib: RegularFileProperty

    /** LLVM with llvm-objcopy/llvm-nm for the payload tools (KONAN_LLVM_HOME); unset = the tools' default. */
    abstract val llvmHome: DirectoryProperty

    /**
     * KALDO_KEEP_COMPACT_UNWIND at package time, so exceptions can unwind through JIT frames (INTERFACE §7:
     * a correctness requirement). Default true; since B7 the synthesized FDEs cost ~0.7 s at startup.
     */
    abstract val keepCompactUnwind: Property<Boolean>

    /**
     * What `hotReload` does when the compatibility classifier (INTERFACE §10) says a change cannot be hot
     * replaced (class layout, signatures, inline/suspend bodies, top-level properties...). true (default):
     * rebuild the app and relaunch it, restoring the state the app saved (`--hr-state`, §10.3).
     * false: print the reasons and fail without sending anything (same as `hotReload --no-warm`).
     */
    abstract val warmRestart: Property<Boolean>

    /**
     * Toolchain .6+: after every successful reload the app invalidates all Compose compositions, so call sites with
     * static arguments run the new code too (`remember` state is kept; at most ~70 ms more per reload).
     * true (default) or false (`KONAN_HR_FORCE_RECOMPOSE=0`: the .5 behaviour, unchanged call sites may stay stale).
     * Also `-Pcmp.hotreload.forceRecompose=false`. Takes effect at the next app launch (hotReloadRun or a warm restart).
     */
    abstract val forceRecompose: Property<Boolean>

    /**
     * Toolchain .8+: a reload is applied on the app's main thread. When the main thread does not take it within this
     * many milliseconds (default 2000; KONAN_HR_MAIN_WAIT_MS), the patch is not applied and hotReload fails with "the app
     * was busy"; save again or rerun. Also `-Pcmp.hotreload.mainWaitMs=…`. Takes effect at the next app launch.
     */
    abstract val mainWaitMs: Property<Int>

    /**
     * UIDeviceFamily of the Info.plist the plugin generates for an app module without one (#9): listOf(1) iPhone
     * (default), listOf(1, 2) iPhone and iPad, listOf(2) iPad. Also `-Pcmp.hotreload.deviceFamily=1,2`. An app module
     * that has its own Info.plist uses it as is (this setting is then ignored, with a warning).
     */
    abstract val deviceFamily: ListProperty<Int>

    /** Klibs to link in addition to the toolchain's and the modules' Gradle dependencies. */
    abstract val extraKlibs: ConfigurableFileCollection

    /**
     * Link the klibs of the modules' Gradle dependencies (configuration `iosSimulatorArm64CompileKlibraries`,
     * transitive). Kotlin stdlib, Compose and the AndroidX libraries the toolchain ships are replaced by the
     * toolchain's copies. Default true.
     */
    abstract val resolveDependencyKlibs: Property<Boolean>

    val simulator: SimulatorSpec = objects.newInstance(SimulatorSpec::class.java)
    fun simulator(action: Action<in SimulatorSpec>) = action.execute(simulator)

    /**
     * Modules. When none are declared, every subproject with src/main/kotlin takes part: the one with
     * src/main/kotlin/main.kt is the app, the others are libraries in path order.
     */
    val modules: NamedDomainObjectContainer<HotReloadModule> = objects.domainObjectContainer(HotReloadModule::class.java)

    fun app(path: String, action: Action<in HotReloadModule> = Action { }) {
        modules.register(path) {
            kind.set(ModuleKind.APP)
            action.execute(this)
        }
    }

    /** Modules of included builds compiled from source (0.2.2), in dependency order (a module after those it uses). */
    val includedModules: NamedDomainObjectContainer<IncludedModuleSpec> = objects.domainObjectContainer(IncludedModuleSpec::class.java)

    /** Declaration order of [includedModules] (the container itself iterates by name). */
    internal val includedOrder = mutableListOf<String>()

    /**
     * `includedModule("lib-build", ":core")`: the module :core of the included build lib-build takes part in hot reload
     * (its changes are HOT/WARM like any library module). Without this, a module of an included build that the app
     * depends on is linked as the klib that build produces: it works, and a change in it restarts the app.
     */
    fun includedModule(build: String, path: String, action: Action<in IncludedModuleSpec> = Action { }) {
        val p = if (path.startsWith(":")) path else ":$path"
        includedOrder += ":$build$p"
        includedModules.register(":$build$p") {
            this.build.set(build)
            projectPath.set(p)
            action.execute(this)
        }
    }

    fun library(path: String, action: Action<in HotReloadModule> = Action { }) {
        modules.register(path) {
            kind.set(ModuleKind.LIBRARY)
            action.execute(this)
        }
    }
}
