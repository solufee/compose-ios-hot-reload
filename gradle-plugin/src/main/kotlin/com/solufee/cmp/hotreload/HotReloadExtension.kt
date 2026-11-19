package com.solufee.cmp.hotreload

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

enum class ModuleKind { APP, LIBRARY }

/** One Gradle module that takes part in hot reload. The name is the project path, e.g. ":ui". */
abstract class HotReloadModule @Inject constructor(private val name: String) : Named {
    override fun getName(): String = name

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

    fun library(path: String, action: Action<in HotReloadModule> = Action { }) {
        modules.register(path) {
            kind.set(ModuleKind.LIBRARY)
            action.execute(this)
        }
    }
}
