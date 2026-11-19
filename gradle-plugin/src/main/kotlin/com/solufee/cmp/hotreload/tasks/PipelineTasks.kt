package com.solufee.cmp.hotreload.tasks

import com.solufee.cmp.hotreload.Compat
import com.solufee.cmp.hotreload.Daemons
import com.solufee.cmp.hotreload.HrSettings
import com.solufee.cmp.hotreload.Launcher
import com.solufee.cmp.hotreload.Pipeline
import com.solufee.cmp.hotreload.Tools
import com.solufee.cmp.hotreload.WarmRestart
import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File

/** Base of every pipeline task: carries the resolved settings (configuration-cache safe). */
abstract class HotReloadTask : DefaultTask() {
    @get:Internal
    abstract val settings: Property<HrSettings>

    protected fun tools(s: HrSettings) = Tools.extract(File(s.workDir), logger)
    protected fun daemons(s: HrSettings, tools: Tools): Daemons? =
        if (s.useDaemon) Daemons(s, logger).also { it.ensure(tools) } else null
}

/** Compiles one library module to a klib. Up to date when its sources and dependency klibs did not change. */
abstract class CompileKlibTask : HotReloadTask() {
    @get:Input
    abstract val modulePath: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyKlibs: ConfigurableFileCollection

    /** Toolchain identity: a different compiler or Compose plugin must rebuild the klib. */
    @get:Input
    abstract val toolchainKey: Property<String>

    @get:OutputFile
    abstract val klib: RegularFileProperty

    @TaskAction
    fun compile() {
        val s = settings.get()
        val m = s.modules.single { it.path == modulePath.get() }
        val tools = tools(s)
        Pipeline(s, logger).compileLibrary(m, daemons(s, tools))
    }
}

abstract class PackageTask : HotReloadTask() {
    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun pkg() {
        val s = settings.get()
        val tools = tools(s)
        val daemons = daemons(s, tools)
        val pipeline = Pipeline(s, logger)
        pipeline.packageApp(tools, daemons)
        // INTERFACE §10.2: package ends with the classifier's baseline snapshots of what it built.
        Compat(s, logger, pipeline, tools).baseline(daemons)
    }
}

/**
 * Classify, then hot-replace or warm-restart (INTERFACE §10.2). The classifier runs before anything is
 * compiled for the patch or sent; a tool error (exit 2) fails the task without sending.
 */
abstract class ReloadTask : HotReloadTask() {
    init {
        outputs.upToDateWhen { false }
        noWarm.convention(false)
    }

    /** `./gradlew hotReload --no-warm`: like `warmRestart = false` for this run. */
    @get:Internal
    @get:Option(option = "no-warm", description = "On a change that needs a warm restart, only print the reasons and fail; send nothing")
    abstract val noWarm: Property<Boolean>

    @TaskAction
    fun reload() {
        val s = settings.get()
        val tools = tools(s)
        val daemons = daemons(s, tools)
        val pipeline = Pipeline(s, logger)
        val compat = Compat(s, logger, pipeline, tools)
        if (!compat.hasBaseline()) {
            logger.warn("CMP hot reload: no classifier baseline in ${s.compatDir}; run ./gradlew hotReloadPackage (classifier skipped)")
            pipeline.reload(daemons)
            return
        }
        // v8: classify in the background while the patch compiles; send only after a HOT verdict.
        val pending = compat.startCheck(daemons)
        val t0 = System.currentTimeMillis()
        var patchError: Exception? = null
        val patch = try {
            pipeline.preparePatch(daemons)
        } catch (e: Exception) {
            patchError = e
            null
        }
        val tReady = System.currentTimeMillis()
        // A compile error in the app also breaks the classifier's app klib: report the compiler's message.
        val r = try {
            compat.await(pending)
        } catch (e: GradleException) {
            throw patchError ?: e
        }
        val waited = System.currentTimeMillis() - tReady
        if (r.verdict == Compat.Verdict.HOT) {
            if (patchError != null) throw patchError
            logger.lifecycle("compat: HOT (classifier ${r.ms} ms, waited ${waited} ms after the patch was ready)")
            pipeline.sendPatch(patch!!)
            compat.commit()
            return
        }
        // A change that needs a warm restart may also fail to build as a patch; the warm restart rebuilds anyway.
        logger.lifecycle("compat: WARM (classifier ${r.ms} ms, patch ${if (patchError == null) "built in ${tReady - t0} ms" else "failed"}; not sent)\n" +
            r.reasons.joinToString("\n"))
        if (noWarm.get() || !s.warmRestart) {
            val why = if (noWarm.get()) "--no-warm" else "cmpHotReload.warmRestart = false"
            throw GradleException("CMP hot reload: this change needs a warm restart; patch not sent ($why):\n" + r.reasons.joinToString("\n"))
        }
        logger.lifecycle("compat: warm restart required; rebuilding and relaunching")
        WarmRestart(s, logger).run(pipeline, tools, daemons, compat)
    }
}

abstract class RunTask : HotReloadTask() {
    init {
        outputs.upToDateWhen { false }
        appArgs.convention("")
    }

    /** `./gradlew hotReloadRun --args="--hr-state=2,3"`: arguments for the app (split on spaces). */
    @get:Internal
    @get:Option(option = "args", description = "Arguments passed to the app, separated by spaces (e.g. --hr-state=2,3)")
    abstract val appArgs: Property<String>

    @TaskAction
    fun run() {
        val s = settings.get()
        Launcher(s, logger).launch(Pipeline(s, logger), appArgs.get().trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
    }
}

abstract class DaemonStartTask : HotReloadTask() {
    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun start() {
        val s = settings.get()
        Daemons(s, logger).ensure(tools(s))
    }
}

/** Stops this project's daemons and deletes build/hotreload (app, caches, merge cache, classifier snapshots). */
abstract class CleanTask : HotReloadTask() {
    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun clean() {
        val s = settings.get()
        Daemons(s, logger).stop()
        val work = File(s.workDir)
        val mb = if (work.exists()) work.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024) else 0
        work.deleteRecursively()
        logger.lifecycle("CMP hot reload: deleted ${work.path} ($mb MB)")
    }
}

abstract class DaemonStopTask : HotReloadTask() {
    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun stop() {
        val d = Daemons(settings.get(), logger)
        val pids = d.pids()
        d.stop()
        if (pids.isEmpty()) logger.lifecycle("CMP hot reload: no compile daemons were running")
    }
}

/** Release zero-change check: see ReleaseCheck. -Pcmp.hotreload.releaseBaseline=true records the baseline. */
abstract class VerifyReleaseTask : HotReloadTask() {
    @get:Input
    abstract val recordBaseline: Property<Boolean>

    /** Baseline mode: at most this many builds while looking for both known __text variants. */
    @get:Input
    abstract val maxBaselineRuns: Property<Int>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        com.solufee.cmp.hotreload.ReleaseCheck(settings.get(), logger).run(recordBaseline.get(), maxBaselineRuns.get())
    }
}
