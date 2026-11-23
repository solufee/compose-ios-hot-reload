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

    /** #76: the project's compiler plugin jars (task input) and, per module, which jars it uses ("modulePath|jar"). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val compilerPluginFiles: ConfigurableFileCollection

    @get:Internal
    abstract val compilerPluginIds: ListProperty<String>

    /** #122: the Gradle property kotlin.internal.collectFUSMetrics (unset: null). */
    @get:Internal
    abstract val kotlinFusMetrics: Property<String>

    /** #122: <Gradle user home>/kotlin-profile, where the Kotlin plugin keeps its build statistics. */
    @get:Internal
    abstract val kotlinProfileDir: Property<String>

    /** #122: warns once per [session] when the Kotlin plugin's statistics slow every build down (see [KotlinProfileHint]). */
    protected fun warnKotlinProfile(session: String) {
        val dir = kotlinProfileDir.orNull ?: return
        com.solufee.cmp.hotreload.KotlinProfileHint.once(session,
            com.solufee.cmp.hotreload.KotlinProfileHint.warning(File(dir), kotlinFusMetrics.orNull))?.let { logger.warn(it) }
    }

    /** #116: projects of other builds linked as their klibs, "display name<TAB>build name<TAB>project path". */
    @get:Internal
    abstract val externalProjects: ListProperty<String>

    /** Settings with the dependency klibs that are linked (the toolchain's own copies taken out). */
    /** [report]: print the dependency summary (only the package, reload and release tasks do; library compiles stay quiet). */
    /** #76: each module gets its project compiler plugin jars (the Compose plugin excluded: the toolchain's own is used). */
    private fun withCompilerPlugins(s: HrSettings): HrSettings {
        val ids = compilerPluginIds.getOrElse(emptyList()).map { it.substringBefore('|') to File(it.substringAfter('|')) }
        if (ids.isEmpty()) return s
        return s.copy(modules = s.modules.map { m ->
            m.copy(compilerPlugins = com.solufee.cmp.hotreload.Kmp.withoutComposeJars(ids.filter { it.first == m.path }.map { it.second }).map { it.path })
        })
    }

    /** hotReloadPackage's work; also what hotReloadRun does when there is no package yet (#93). */
    protected fun packageApp() {
        val s = effectiveSettings()
        val tools = tools(s)
        val daemons = daemons(s, tools)
        val pipeline = Pipeline(s, logger)
        // INTERFACE §10.2: package ends with the classifier's baseline snapshots of what it built; it runs beside
        // merge/embed/bundle (#52).
        pipeline.packageApp(tools, daemons, beside = { Compat(s, logger, pipeline, tools).baseline(daemons) })
    }

    protected fun effectiveSettings(report: Boolean = true, reportAlways: Boolean = true): HrSettings {
        val s = withCompilerPlugins(settings.get())
        val ids = externalKlibIds.getOrElse(emptyList())
        if (ids.isEmpty()) return s
        val via = externalKlibVia.getOrElse(emptyList()).associate { it.substringBefore('|') to it.substringAfter('|') }
        val all = ids.map { val parts = it.split("|", limit = 2); com.solufee.cmp.hotreload.Kmp.Dependency(parts[0], File(parts[1]), via[parts[0]]) }
            .filter { it.file.name.endsWith(".klib") || it.file.isDirectory }
        val fromSource = fromSourceModules.getOrElse(emptyList()).map { it.split("|") }
        val (dropped, deps) = all.partition { d -> fromSource.any { f -> com.solufee.cmp.hotreload.Kmp.isOwnBuildKlib(d, f[0], f[1], File(f[2]), File(f[3])) } }
        val toolchain = Pipeline.externalKlibs(File(s.klibList).readLines(), File(s.dist))
        val sel = com.solufee.cmp.hotreload.Kmp.selectKlibs(toolchain, File(s.dist, "klib/common/stdlib"), deps, composeVersion.get(),
            if (report) logger else null, summary = false)
        if (report) {
            val lines = mutableListOf<String>()
            if (dropped.isNotEmpty()) {
                lines += "dependency klibs: left out ${dropped.size} klib(s) of included-build modules compiled from source: " + dropped.joinToString { it.id }
            }
            lines += com.solufee.cmp.hotreload.Kmp.selectionReport(sel)
            // Composite build: projects of another build that were not declared with includedModule(...) are linked
            // as the klibs their build produces (only this build's projects and declared modules are filtered out).
            val projects = externalProjects.getOrElse(emptyList()).map { it.split('\t') }.filter { it.size == 3 }.associate { it[0] to (it[1] to it[2]) }
            deps.filter { it.id.startsWith("project ") && it.file.path in sel.klibs }.map { it.id }.distinct().forEach { id ->
                lines += com.solufee.cmp.hotreload.Kmp.includedBuildHint(id, projects[id]?.first, projects[id]?.second)
            }
            reportOnce(File(s.workDir, REPORT_FILE), lines, reportAlways)
        }
        return s.copy(extraKlibs = sel.klibs, droppedToolchainKlibs = sel.toolchainDropped)
    }

    /**
     * #116: the dependency report is printed by every package, run and release check; a hot reload (and each change in
     * watch mode) prints it only when it differs from the last one printed, otherwise at info level.
     */
    private fun reportOnce(file: File, lines: List<String>, always: Boolean) {
        if (reportNow(file, lines, always)) lines.forEach { logger.lifecycle(it) } else lines.forEach { logger.info(it) }
    }

    companion object {
        /** #116: the dependency report last printed (hotReload prints it again only when it changed). */
        const val REPORT_FILE = "dependency-report.txt"

        /** True (and [file] updated) when the report is printed: [always], or it differs from the one last printed. */
        fun reportNow(file: File, lines: List<String>, always: Boolean): Boolean {
            val text = lines.joinToString("\n")
            if (!always && file.isFile && file.readText() == text) return false
            file.parentFile.mkdirs()
            file.writeText(text)
            return true
        }
    }

    protected fun tools(s: HrSettings) = Tools.extract(File(s.workDir), logger)
    protected fun daemons(s: HrSettings, tools: Tools): Daemons? =
        if (s.useDaemon) Daemons(s, logger).also { it.ensure(tools) } else null
}

/**
 * #98: records what the hot reload state is compiled with (plugin version, toolchain contents, compiler plugins and
 * options, ABI switches) and clears the compiled state when that changed. Runs before every task that compiles.
 */
abstract class BuildIdentityTask : HotReloadTask() {
    /** True when this run packages or launches (the state may be cleared); false for a hot reload only (#98). */
    @get:Input
    abstract val clearOnChange: Property<Boolean>

    init {
        outputs.upToDateWhen { false }
        clearOnChange.convention(true)
    }

    @TaskAction
    fun check() {
        val s = effectiveSettings(report = false)
        if (clearOnChange.get()) com.solufee.cmp.hotreload.BuildIdentity.ensure(s, logger, stopDaemons = { Daemons(s, logger).stop() })
        else com.solufee.cmp.hotreload.BuildIdentity.report(s, logger)
    }
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

    /** #98: the build identity file (contents: plugin version, toolchain contents, compiler plugins); a change rebuilds. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val buildIdentity: ConfigurableFileCollection

    @get:OutputFile
    abstract val klib: RegularFileProperty

    init {
        // #98: hotReloadBuildIdentity (and hotReloadClean) delete klibs earlier in the same build, outside Gradle's view of
        // this task's output; Gradle's file system state can then still list the old klib and call this task up to date.
        // Ask the file system itself.
        outputs.upToDateWhen { (it as CompileKlibTask).klib.get().asFile.isFile }
    }

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
    fun pkg() = packageApp()
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
    internal fun warnKotlinProfileAtWatch() =
        warnKotlinProfile(if (continuous.get()) "watch:${settings.get().rootDir}" else "watch-once:${System.nanoTime()}")

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
        val s = effectiveSettings(reportAlways = false)
        val tools = tools(s)
        val daemons = daemons(s, tools)
        val pipeline = Pipeline(s, logger)
        val compat = Compat(s, logger, pipeline, tools)
        if (!compat.hasBaseline()) {
            logger.warn("CMP hot reload: no classifier baseline in ${s.compatDir}; run ./gradlew hotReloadPackage (classifier skipped)")
            pipeline.reload(daemons, tools)
            return
        }
        // v8: classify in the background while the patch compiles; send only after a HOT verdict.
        val pending = compat.startCheck(daemons)
        val t0 = System.currentTimeMillis()
        var patchError: Exception? = null
        val patch = try {
            pipeline.preparePatch(daemons, tools)
        } catch (e: Exception) {
            patchError = e
            null
        }
        val tReady = System.currentTimeMillis()
        // A compile error in the app also breaks the classifier's app klib: report the compiler's message.
        val r0 = try {
            compat.await(pending)
        } catch (e: GradleException) {
            throw patchError ?: e
        }
        // #76: generated code that gains or loses declarations, or class annotations that project compiler plugins read,
        // change what was built at start: a warm restart, whatever the classifier and the patch compile saw.
        val extra = listOfNotNull(com.solufee.cmp.hotreload.GeneratedDeclarations.changed(s), com.solufee.cmp.hotreload.PluginAnnotations.changed(s)) +
            ((patchError as? com.solufee.cmp.hotreload.NeedsWarm)?.reasons ?: emptyList())
        val r = if (extra.isEmpty()) r0 else r0.copy(verdict = Compat.Verdict.WARM, reasons = r0.reasons + extra)
        val waited = System.currentTimeMillis() - tReady
        if (r.verdict == Compat.Verdict.HOT) {
            if (patchError is com.solufee.cmp.hotreload.NothingChanged) {
                logger.lifecycle(patchError.message!!.removePrefix("CMP hot reload: "))
                return
            }
            if (patchError != null) throw patchError
            logger.lifecycle("compat: HOT (classifier ${r.ms} ms, waited ${waited} ms after the patch was ready)")
            r.notes.forEach { logger.lifecycle(hotNote(it, pipeline.stableKeysState == "on")) }
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
        // #122: a continuous watch warns at its first build only (once per project in this Gradle daemon); a single run each time
        t.warnKotlinProfileAtWatch()
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
        // #122: once per hotReloadRun (a new app session)
        warnKotlinProfile("run:${System.nanoTime()}")
        // #93: after hotReloadClean (or before the first package) there is nothing to launch; package once first. An
        // existing package is launched as it is (hotReloadPackage is never up to date, so depending on it would repackage
        // on every run).
        if (!Pipeline(s, logger).packagedAppReady()) {
            logger.lifecycle("CMP hot reload: no packaged app in ${s.workDir} (hotReloadClean, or never packaged); packaging it first")
            packageApp()
        }
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
        // #107: the perf maps of app processes this project started (their pids are recorded under work)
        com.solufee.cmp.hotreload.PerfMaps.clean(work, logger)
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
