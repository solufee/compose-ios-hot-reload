package com.solufee.cmp.hotreload.tasks

import com.solufee.cmp.hotreload.Compat
import com.solufee.cmp.hotreload.Daemons
import com.solufee.cmp.hotreload.HrSettings
import com.solufee.cmp.hotreload.Launcher
import com.solufee.cmp.hotreload.Pipeline
import com.solufee.cmp.hotreload.Tools
import com.solufee.cmp.hotreload.WarmAdvice
import com.solufee.cmp.hotreload.WarmRestart
import com.solufee.cmp.hotreload.hotNote
import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.IgnoreEmptyDirectories
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

    /** Klibs of the modules' Gradle dependencies and cmpHotReload.extraKlibs (0.2.0). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val externalKlibFiles: ConfigurableFileCollection

    /** "coordinates|path" of each dependency klib, for replacement and error messages. */
    @get:Internal
    abstract val externalKlibIds: ListProperty<String>

    /** Included-build modules compiled from source (`build|path|dir|build dir`): their own build's klibs are left out. */
    @get:Internal
    abstract val fromSourceModules: ListProperty<String>

    /** `component|direct dependency` of the resolved graphs, for "pulled in by" in messages. */
    @get:Internal
    abstract val externalKlibVia: ListProperty<String>

    /** Compose version of the toolchain's fork (for messages). */
    @get:Internal
    abstract val composeVersion: Property<String>

    /** Settings with the dependency klibs that are linked (the toolchain's own copies taken out). */
    /** [report]: print the dependency summary (only the package, reload and release tasks do; library compiles stay quiet). */
    protected fun effectiveSettings(report: Boolean = true): HrSettings {
        val s = settings.get()
        val ids = externalKlibIds.getOrElse(emptyList())
        if (ids.isEmpty()) return s
        val via = externalKlibVia.getOrElse(emptyList()).associate { it.substringBefore('|') to it.substringAfter('|') }
        val all = ids.map { val parts = it.split("|", limit = 2); com.solufee.cmp.hotreload.Kmp.Dependency(parts[0], File(parts[1]), via[parts[0]]) }
            .filter { it.file.name.endsWith(".klib") || it.file.isDirectory }
        val fromSource = fromSourceModules.getOrElse(emptyList()).map { it.split("|") }
        val (dropped, deps) = all.partition { d -> fromSource.any { f -> com.solufee.cmp.hotreload.Kmp.isOwnBuildKlib(d, f[0], f[1], File(f[2]), File(f[3])) } }
        if (report && dropped.isNotEmpty()) {
            logger.lifecycle("dependency klibs: left out ${dropped.size} klib(s) of included-build modules compiled from source: " +
                dropped.joinToString { it.id })
        }
        val toolchain = Pipeline.externalKlibs(File(s.klibList).readLines(), File(s.dist))
        val sel = com.solufee.cmp.hotreload.Kmp.selectKlibs(toolchain, File(s.dist, "klib/common/stdlib"), deps, composeVersion.get(), if (report) logger else null)
        if (report) {
            // Composite build: projects of another build that were not declared with includedModule(...) are linked
            // as the klibs their build produces (only this build's projects and declared modules are filtered out).
            deps.filter { it.id.startsWith("project ") && it.file.path in sel.klibs }.map { it.id }.distinct().forEach { id ->
                val path = id.removePrefix("project ").trim()
                val build = path.trim(':').substringBefore(':')
                val module = ":" + path.trim(':').substringAfter(':', "")
                logger.lifecycle("dependency klibs: $id comes from the included build $build and is linked as the klib that build produces; " +
                    "its code is not hot reloaded (a change there restarts the app). To hot reload it: cmpHotReload { includedModule(\"$build\", \"$module\") }")
            }
        }
        return s.copy(extraKlibs = sel.klibs)
    }

    protected fun tools(s: HrSettings) = Tools.extract(File(s.workDir), logger)
    protected fun daemons(s: HrSettings, tools: Tools): Daemons? =
        if (s.useDaemon) Daemons(s, logger).also { it.ensure(tools) } else null
}

/** Compiles one library module to a klib. Up to date when its sources and dependency klibs did not change. */
abstract class CompileKlibTask : HotReloadTask() {
    @get:Input
    abstract val modulePath: Property<String>

    /** Every source directory of the module (all its fragments). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

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
        val s = effectiveSettings(report = false)
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
        val s = effectiveSettings()
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
/**
 * `hotReload`. Never up to date: it compares against what the running app has (the classifier baseline and the
 * applied stamp) and decides itself, so "no change" is always the same answer - `nothing changed`, exit 0 (#7) -
 * never an UP-TO-DATE skip. Its inputs are declared (#8) so that Gradle's continuous mode (`-t`) re-runs it on
 * every saved change.
 */
abstract class ReloadTask : HotReloadTask() {
    init {
        outputs.upToDateWhen { false }
        noWarm.convention(false)
        continuous.convention(false)
    }

    /**
     * Kotlin sources (every fragment of every hot module, included-build modules too) and Compose resources
     * (src/<set>/composeResources) of the hot modules. Generated code is not listed: it follows the tasks this one
     * depends on.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    abstract val watchedSources: ConfigurableFileCollection

    /** Whether Gradle runs in continuous mode (`-t`), for the messages of hotReloadWatch. */
    @get:Internal
    abstract val continuous: Property<Boolean>

    /** `./gradlew hotReload --no-warm`: like `warmRestart = false` for this run. */
    @get:Internal
    @get:Option(option = "no-warm", description = "On a change that needs a warm restart, only print the reasons and fail; send nothing")
    abstract val noWarm: Property<Boolean>

    @TaskAction
    fun reload() {
        val s = effectiveSettings()
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
            if (patchError is com.solufee.cmp.hotreload.NothingChanged) {
                logger.lifecycle(patchError.message!!.removePrefix("CMP hot reload: "))
                return
            }
            if (patchError != null) throw patchError
            logger.lifecycle("compat: HOT (classifier ${r.ms} ms, waited ${waited} ms after the patch was ready)")
            r.notes.forEach { logger.lifecycle(hotNote(it)) }
            pipeline.sendPatch(patch!!)
            compat.commit()
            return
        }
        // A change that needs a warm restart may also fail to build as a patch; the warm restart rebuilds anyway.
        // #18: every reason with one actionable sentence, and a summary line at the end.
        logger.lifecycle("compat: WARM (classifier ${r.ms} ms, patch ${if (patchError == null) "built in ${tReady - t0} ms" else "failed"}; not sent)\n" +
            WarmAdvice.annotate(r.reasons).joinToString("\n"))
        if (noWarm.get() || !s.warmRestart) {
            val why = if (noWarm.get()) "--no-warm" else "cmpHotReload.warmRestart = false"
            logger.lifecycle(WarmAdvice.summary(r.reasons.size, null, why))
            throw GradleException("CMP hot reload: this change needs a warm restart; patch not sent ($why):\n" + r.reasons.joinToString("\n"))
        }
        logger.lifecycle("compat: warm restart required; rebuilding and relaunching")
        val warmMs = WarmRestart(s, logger).run(pipeline, tools, daemons, compat)
        logger.lifecycle(WarmAdvice.summary(r.reasons.size, warmMs))
    }
}

/**
 * `hotReloadWatch -t`: a hot reload on every saved change (#8). It is a `hotReload` task; the watching is Gradle's
 * continuous mode, which watches its inputs and those of the tasks it depends on (Compose resource generators
 * included), waits for a quiet period, re-runs with the configuration cache and stops on Ctrl-C; the compile daemons
 * stay up between runs, and a failed run (compile error) does not end the watch. This action only says what is watched.
 */
class WatchAnnounce : org.gradle.api.Action<org.gradle.api.Task> {
    override fun execute(task: org.gradle.api.Task) {
        val t = task as ReloadTask
        val files = t.watchedSources.files.sumOf { f -> if (f.isDirectory) f.walkTopDown().count { it.isFile } else 1 }
        if (t.continuous.get()) {
            t.logger.lifecycle("hotReloadWatch: watching $files source and resource files of the hot modules; save a change to reload, Ctrl-C to stop")
        } else {
            t.logger.lifecycle("hotReloadWatch: one run. To keep watching: ./gradlew hotReloadWatch -t (Gradle continuous build)")
        }
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

    /** Baseline mode: number of builds (default 1); more than one also checks that they agree. */
    @get:Input
    abstract val maxBaselineRuns: Property<Int>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        com.solufee.cmp.hotreload.ReleaseCheck(effectiveSettings(), logger).run(recordBaseline.get(), maxBaselineRuns.get())
    }
}
