package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File

/**
 * The hot-reload pipeline, implemented in the plugin (P1). It calls only the tools and conventions of
 * docs/INTERFACE.md: kotlinc-native / HrCompileDaemon, merge-payloads.py, embed-merged.py, the reload protocol.
 */
/**
 * No module object was rebuilt since the running app last got new code (#7): there is nothing to send. Not an
 * error: `hotReload` reports it and succeeds, so an edit-free run (or the first run of `-t`) does not fail.
 */
/** The running app did not apply a sent patch (toolchain .8+: `HR reload: NOT applied`). hotReload fails; a watch keeps watching. */
class PatchNotApplied(message: String) : GradleException(message)

/** What the app's log says about a sent patch (lines since sending). */
sealed class ReloadOutcome {
    data class NotApplied(val line: String, val waitMs: Long?) : ReloadOutcome()
    data class Applied(val line: String, val waitMs: Long?, val runMs: Long?) : ReloadOutcome()
    /** Only the loader's `[kaldo] ::` lines so far. */
    object Loaded : ReloadOutcome()
    object None : ReloadOutcome()
}

/** Reads the app's answer to a patch from its log lines since the patch was sent (#40). */
fun reloadOutcome(sinceSend: String): ReloadOutcome {
    val lines = sinceSend.replace('\r', '\n').lines()
    lines.firstOrNull { it.contains("HR reload: NOT applied") }?.let { l ->
        val ms = Regex("""within (\d+) ms""").find(l)?.groupValues?.get(1)?.toLongOrNull()
        return ReloadOutcome.NotApplied(l.substring(l.indexOf("HR reload:")).trim(), ms)
    }
    lines.firstOrNull { it.contains("HR reload: ran") }?.let { l ->
        fun num(key: String) = Regex("""\b$key=(\d+)""").find(l)?.groupValues?.get(1)?.toLongOrNull()
        return ReloadOutcome.Applied(l.substring(l.indexOf("HR reload:")).trim(), num("waitMs"), num("runMs"))
    }
    return if (lines.any { it.contains("[kaldo] ::") }) ReloadOutcome.Loaded else ReloadOutcome.None
}

class NothingChanged(val modules: Int) :
    GradleException("CMP hot reload: nothing changed ($modules hot module${if (modules == 1) "" else "s"} checked); nothing sent")

class Pipeline(private val s: HrSettings, private val logger: Logger) {
    private val work = File(s.workDir)

    // ---------------------------------------------------------------- arguments

    /**
     * Klibs from the toolchain klib list, minus the dist's own stdlib/platform klibs (<dist>/klib/, which the
     * compiler adds implicitly). Everything else is linked wherever it lives, including public klibs that the
     * toolchain download put next to the dist; nothing is assumed to be in ~/.gradle.
     */
    fun externalKlibs(): List<String> = externalKlibs(File(s.klibList).readLines(), File(s.dist)) + s.extraKlibs


    /** Longest common directory of the external klibs: caches are auto-built for everything below it. */
    fun autoCacheFrom(klibs: List<String>): String {
        if (klibs.isEmpty()) return File(System.getProperty("user.home"), ".gradle/caches").path
        var prefix = File(klibs.first()).parentFile
        for (k in klibs.drop(1)) {
            while (prefix != null && !File(k).canonicalPath.startsWith(prefix.canonicalPath + "/")) prefix = prefix.parentFile
        }
        return prefix?.path ?: "/"
    }

    /**
     * -Xauto-cache-from roots: the toolchain's klibs, and (separately) the dependency klibs from Gradle. Never a
     * directory that also holds the project's own klibs - those are compiled per file so they can be hot-swapped.
     */
    fun autoCacheRoots(): List<String> {
        val toolchain = externalKlibs(File(s.klibList).readLines(), File(s.dist))
        val roots = mutableListOf(autoCacheFrom(toolchain))
        if (s.extraKlibs.isNotEmpty()) {
            val common = autoCacheFrom(s.extraKlibs)
            val home = File(System.getProperty("user.home")).canonicalPath
            val tooBroad = common == "/" || File(common).canonicalPath == home || File(s.rootDir).canonicalPath.startsWith(File(common).canonicalPath + "/")
            roots += if (tooBroad) s.extraKlibs.map { File(it).parent }.distinct() else listOf(common)
        }
        return roots.distinct()
    }

    /**
     * Sources of a module: its directories as free arguments when it has one fragment (as in 0.1.x); otherwise a
     * multiplatform compilation - every fragment with its own files (-Xfragment-sources) and its dependsOn edges
     * (-Xfragment-refines). All klibs go to every fragment through -l.
     */
    fun sourceArgs(m: HrModule): List<String> {
        // The generated UIKit entry (iosEntry) joins the app's leaf fragment (the platform one, listed last).
        val withEntry = m.kind == ModuleKind.APP && s.iosEntry != null
        val fragments = m.fragments.mapIndexed { i, f -> if (withEntry && i == m.fragments.lastIndex) f.copy(dirs = f.dirs + s.generatedDir.path) else f }
        if (fragments.size == 1) return fragments.single().dirs.filter { File(it).exists() }
        val args = mutableListOf("-Xmulti-platform", "-Xexpect-actual-classes", "-Xfragments=" + fragments.joinToString(",") { it.name })
        for (f in fragments) f.refines.forEach { args += "-Xfragment-refines=${f.name}:$it" }
        val files = mutableListOf<String>()
        for (f in fragments) {
            f.dirs.map(::File).filter { it.isDirectory }.flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
                .sortedBy { it.path }.forEach { file ->
                    args += "-Xfragment-sources=${f.name}:${file.path}"
                    files += file.path
                }
        }
        return args + files
    }

    /** When the running app last got new code: set by package and after every sent patch. */
    private val appliedStamp get() = File(work, "applied.stamp")

    private val pendingCaches get() = File(work, "pending-caches.txt")

    /** Digest of the hot sources the running app was given (package or a sent patch), for "nothing changed" (#7). */
    val appliedSources get() = File(work, "applied-sources.sha256")

    /**
     * sha256 over every Kotlin source of the hot modules (all fragments, generated code and the plugin's entry
     * included), by relative path and content: an edit changes it, touching a file or saving it unchanged does not.
     */
    fun sourcesDigest(): String = Tools.sha256(
        (s.modules.flatMap { it.sourceDirs } + s.generatedDir.path).distinct().map(::File).filter { it.isDirectory }.flatMap { d ->
            d.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { f -> "${f.path}\u0000${Tools.sha256(f.readBytes())}" }.toList()
        }.sorted().joinToString("\n").toByteArray(),
    )

    private fun markApplied(at: Long) {
        appliedSources.parentFile.mkdirs()
        appliedSources.writeText(sourcesDigest())
        pendingCaches.delete()
        appliedStamp.parentFile.mkdirs()
        appliedStamp.writeText("")
        appliedStamp.setLastModified(at)
    }

    /** sha256 of every Compose resource file copied into the app at package time (`sha  relative path`). */
    val resourceManifest get() = File(work, "compose-resources.sha256")

    /**
     * Compose resources that differ from what the running app was packaged with (they are copied into the .app at
     * package time, so a patch cannot carry them): changed, added or removed relative paths; empty when the
     * manifest is missing (packaged by an older plugin).
     */
    fun resourceChanges(): List<String> {
        if (!resourceManifest.isFile) return emptyList()
        val before = resourceManifest.readLines().filter { it.isNotBlank() }.associate { it.substringAfter("  ") to it.substringBefore("  ") }
        val now = resourceDigests(s.composeResources.map(::File).filter { it.isDirectory })
        return (before.keys + now.keys).sorted().filter { before[it] != now[it] }
    }

    /** Size and mtime of every external klib linked at package time (`stamp  path`). */
    val externalKlibManifest get() = File(work, "external-klibs.stamps")

    private fun externalKlibStamps(): Map<String, String> = s.extraKlibs.map(::File).associate { f ->
        val files = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.toList() else listOf(f)
        f.path to "${files.sumOf { it.length() }}-${files.maxOfOrNull { it.lastModified() } ?: 0}"
    }.toSortedMap()

    /**
     * External klibs that differ from those the running app was packaged with (e.g. a module of an included build that
     * is linked as its klib and was rebuilt): added, removed or changed paths; empty without a manifest.
     */
    fun externalKlibChanges(): List<String> {
        if (!externalKlibManifest.isFile) return emptyList()
        val before = externalKlibManifest.readLines().filter { it.isNotBlank() }.associate { it.substringAfter("  ") to it.substringBefore("  ") }
        val now = externalKlibStamps()
        return (before.keys + now.keys).sorted().filter { before[it] != now[it] }
    }

    /** The UIKit entry generated for [HrSettings.iosEntry] (written by [writeGeneratedMain]). */
    fun generatedMain(): File = File(s.generatedDir, "HotReloadMain.kt")

    /**
     * Apps without a Kotlin `main` (the KMP template: Swift calls MainViewController()) get a minimal UIKit entry
     * that shows the UIViewController returned by [HrSettings.iosEntry]. Written before every compile of the app.
     */
    fun writeGeneratedMain() {
        val entry = s.iosEntry ?: return
        val f = generatedMain()
        f.parentFile.mkdirs()
        val text = generatedMainSource(entry, appInfo().executable)
        if (!f.isFile || f.readText() != text) f.writeText(text)
    }

    private fun klibLinks(m: HrModule?): List<String> {
        val libs = if (m == null) s.libraries else s.libraries.filter { it.path in m.deps }
        return externalKlibs().flatMap { listOf("-l", it) } + libs.flatMap { listOf("-l", s.klibOf(it).path) }
    }

    fun libraryArgs(m: HrModule, out: File = s.klibOf(m)): List<String> = listOf(
        "-target", s.target, "-p", "library", "-g",
        "-Xplugin=${s.composePlugin}", "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
    ) + klibLinks(m) + sourceArgs(m) + listOf("-o", out.path.removeSuffix(".klib"))

    /** Identical for package and reload, so the incremental caches are shared (only -o and the dump differ). */
    fun programArgs(output: File, dumpCaches: File?): List<String> {
        return listOf(
            "-target", s.target, "-p", "program", "-g",
            "-Xcompilation-scheme=split",
            "-Xenable-incremental-compilation", "-Xic-cache-dir=${File(work, "ic").path}",
            "-Xbinary=perFileCacheForStdlib=false",
            *autoCacheRoots().map { "-Xauto-cache-from=$it" }.toTypedArray(),
            "-Xauto-cache-dir=${File(work, "auto-cache").path}",
            "-Xplugin=${s.composePlugin}",
            "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
        ) + s.linkerOptions.map { "-linker-option=$it" } +
            listOf("-linker-option=-force_load", "-linker-option=${s.objcUtilsLib}") +
            klibLinks(null) +
            (dumpCaches?.let { listOf("-Xdump-built-caches-to=${it.path}") } ?: emptyList()) +
            listOf("-o", output.path) + sourceArgs(s.app)
    }

    // ---------------------------------------------------------------- compile

    /**
     * [retryDirectOnError]: when the daemon reports a failure, run the same compile once with kotlinc-native
     * (package via the klib daemon, INTERFACE §5: a real compile error then shows up with the compiler's own output).
     */
    private fun compile(
        args: List<String>, daemonSocket: String?, kind: String, cacheOnly: Boolean, daemons: Daemons?,
        retryDirectOnError: Boolean = false,
    ) {
        if (daemonSocket != null && daemons != null) {
            val offset = daemons.logSize(kind)
            when (val r = CompileDaemonClient.request(daemonSocket, args)) {
                is CompileResult.Ok -> {
                    logger.lifecycle("compile: daemon $kind OK ${r.ms} ms")
                    return
                }
                is CompileResult.Err -> if (retryDirectOnError) {
                    logger.lifecycle("CMP hot reload: compile via the $kind daemon failed (${r.code}, ${r.ms} ms); retrying with kotlinc-native")
                } else {
                    throw GradleException(
                        "CMP hot reload: compilation failed (${r.code}, ${r.ms} ms):\n" + compilerMessages(daemons.logSince(kind, offset)),
                    )
                }
                is CompileResult.Unreachable -> logger.warn("CMP hot reload: daemon $kind unreachable (${r.reason}); compiling directly")
            }
        }
        logger.lifecycle("compile: kotlinc-native")
        Proc.check(listOf(s.kotlinc) + args, env = compileEnvironment(cacheOnly), logger = logger)
    }

    /**
     * Environment of a direct compile. INTERFACE §3.3 #4: the package (host link) compile must not run with
     * KONAN_HOT_RELOAD_CACHE_ONLY; Proc also scrubs it from the caller's environment.
     */
    fun compileEnvironment(cacheOnly: Boolean): Map<String, String> =
        mapOf("JAVA_HOME" to s.javaHome) + (if (cacheOnly) mapOf("KONAN_HOT_RELOAD_CACHE_ONLY" to "1") else emptyMap())

    /** Environment of merge-payloads.py. INTERFACE §3.3 #1: KALDO_KEEP_COMPACT_UNWIND=1. */
    fun mergeEnvironment(packages: List<String>): Map<String, String> = buildMap {
        put("KALDO_HOT_PACKAGES", packages.joinToString(","))
        put("KALDO_HOT_SYMBOLS_OUT", s.hotSymbols.path)
        if (s.keepCompactUnwind) put("KALDO_KEEP_COMPACT_UNWIND", "1")
        s.llvmHome?.let { put("KONAN_LLVM_HOME", it) }
        // INTERFACE §3.1: per-archive results of unchanged library archives are reused (hotReloadClean deletes it).
        put("KALDO_MERGE_CACHE_DIR", mergeCacheDir.path)
    }

    val mergeCacheDir: File get() = File(work, "merge-cache")

    /** Daemon log lines that look like compiler diagnostics, without the daemon's own bookkeeping. */
    private fun compilerMessages(log: String): String =
        log.lines().filterNot { it.startsWith("REQ ") || it.isBlank() }.joinToString("\n").takeLast(6000)

    /** What the last successful klib compile of a module was built from (#21): one `kind key value` line per input. */
    fun klibInputsFile(m: HrModule): File = File(s.klibOf(m).parentFile, "inputs.txt")

    /**
     * Inputs of a library klib (#21), sorted `kind key value` lines: the compiler arguments, every Kotlin source of the
     * module (generated ones included) by path and sha256, each module klib it links by sha256 (an upstream module whose
     * klib bytes changed rebuilds its dependents; klibs are deterministic, so an unchanged upstream rebuild does not),
     * and each dependency klib from Gradle by size and mtime. The toolchain's own klibs are covered by the arguments
     * (paths of the pinned toolchain) and the toolchain identity.
     */
    fun libraryInputs(m: HrModule, out: File = s.klibOf(m)): List<String> {
        val lines = mutableListOf(
            "args - ${Tools.sha256(libraryArgs(m, out).joinToString("\u0000").toByteArray())}",
            "toolchain - ${Tools.sha256("${s.compilerJar}|${s.composePlugin}|${s.klibList}".toByteArray())}",
        )
        m.sourceDirs.map(::File).filter { it.isDirectory }.forEach { d ->
            d.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f -> lines += "source ${f.path} ${Tools.sha256(f.readBytes())}" }
        }
        s.libraries.filter { it.path in m.deps }.forEach { d ->
            val k = s.klibOf(d)
            lines += "module ${d.label} ${if (k.isFile) Tools.sha256(k.readBytes()) else "missing"}"
        }
        externalKlibStamps().forEach { (path, stamp) -> lines += "external $path $stamp" }
        return lines.sorted()
    }

    /**
     * Compiles [m] to its klib unless the klib exists and was built from exactly the same inputs ([libraryInputs]):
     * Gradle may re-run the task for reasons that do not affect this module (#21). Prints why it compiles.
     */
    fun compileLibrary(m: HrModule, daemons: Daemons?) {
        if (compileKlibIfChanged(m, s.klibOf(m), klibInputsFile(m), "klib ${m.label}", daemons)) {
            logger.lifecycle("klib ${m.label}: ${s.klibOf(m).path}")
        }
    }

    /**
     * Compiles [m] to [out] unless [out] exists and was built from the same [libraryInputs] (recorded in [record]).
     * Returns whether it compiled. [what] starts the log lines.
     */
    private fun compileKlibIfChanged(m: HrModule, out: File, record: File, what: String, daemons: Daemons?): Boolean {
        out.parentFile.mkdirs()
        val t0 = System.currentTimeMillis()
        val inputs = libraryInputs(m, out)
        val before = if (record.isFile && out.isFile) record.readLines().filter { it.isNotBlank() } else null
        if (before == inputs) {
            val n = inputs.count { it.startsWith("source ") }
            logger.lifecycle("$what: inputs unchanged ($n source files, ${System.currentTimeMillis() - t0} ms to check); not recompiled")
            return false
        }
        logger.lifecycle("$what: compiling (${klibChangeReason(before, inputs)})")
        record.delete()
        out.deleteRecursively()
        compile(libraryArgs(m, out), if (daemons != null) s.klibSocket else null, "klib", cacheOnly = false, daemons = daemons)
        if (!out.exists()) throw GradleException("CMP hot reload: compiler reported success but ${out.path} is missing")
        record.writeText(inputs.joinToString("\n", postfix = "\n"))
        return true
    }

    /**
     * The app module compiled as a full (IR-bearing) library klib. Only the compatibility classifier uses it:
     * metadata alone does not show inline/suspend body changes (INTERFACE §10.2).
     */
    fun compileAppKlib(daemons: Daemons?): File {
        writeGeneratedMain()
        val out = File(s.compatDir, "app.klib")
        // #21: a warm restart's baseline needs the same app klib the classifier just compiled; it is reused.
        compileKlibIfChanged(s.app, out, File(s.compatDir, "app.inputs.txt"), "app klib (classifier)", daemons)
        return out
    }

    // ---------------------------------------------------------------- packages / module mapping

    /** Packages declared in a module's sources ("" = root package). */
    fun packagesOf(m: HrModule): Set<String> = m.sourceDirs.flatMap { sourcePackages(File(it)) }.toSet() +
        // The UIKit entry the plugin generates for a KMP app (HotReloadMain.kt) is in the root package and compiled with the app.
        (if (m.kind == ModuleKind.APP && s.iosEntry != null) setOf("") else emptySet())

    /** Hot packages for merge-payloads: every package of every library, plus the app's named packages. */
    fun hotPackages(): List<String> =
        (s.libraries.flatMap { packagesOf(it) } + packagesOf(s.app)).filter { it.isNotEmpty() }.distinct().sorted()

    // ---------------------------------------------------------------- package

    data class AppInfo(val executable: String, val bundleId: String)

    fun appInfo(): AppInfo {
        s.infoPlistContent?.let { content ->
            val f = File(s.infoPlist)
            if (!f.isFile || f.readText() != content) { f.parentFile.mkdirs(); f.writeText(content) }
        }
        fun read(key: String) = Proc.check(listOf("/usr/libexec/PlistBuddy", "-c", "Print :$key", s.infoPlist), echo = false).trim()
        return AppInfo(read("CFBundleExecutable"), read("CFBundleIdentifier"))
    }

    fun appBundle(info: AppInfo = appInfo()): File = File(work, "${info.executable}.app")

    /**
     * [daemons]: compile through the klib daemon (it never runs cache-only, INTERFACE §5), falling back to
     * kotlinc-native when it is unreachable or fails.
     */
    fun packageApp(tools: Tools, daemons: Daemons? = null) {
        s.sourceSetWarnings.forEach { logger.warn(it) }
        val t0 = System.currentTimeMillis()
        writeGeneratedMain()
        val info = appInfo()
        val app = s.appDir.apply { mkdirs() }
        listOf("ic", "auto-cache").forEach { File(work, it).mkdirs() }
        // The program's module name comes from its output name and prefixes the exported ObjC classes
        // (e.g. ComposeHRBase*). Keep the reference driver's names: a bare "base" collides at bootstrap.
        val base = File(app, "${info.executable}-base.kexe")
        base.delete()
        compile(programArgs(base, null), if (daemons != null) s.klibSocket else null, "klib", cacheOnly = false, daemons = daemons,
            retryDirectOnError = true)
        if (!base.isFile) throw GradleException("CMP hot reload: the package compile reported success but ${base.path} is missing")
        val tCompile = System.currentTimeMillis()

        val merged = File(app, "merged-bootstrap.o")
        val packages = hotPackages()
        val env = mergeEnvironment(packages)
        logger.lifecycle("hot packages: ${packages.joinToString(",").ifEmpty { "<none>" }}; keep compact unwind: ${s.keepCompactUnwind}")
        Proc.check(listOf("python3", "-I", tools.mergePayloads.path, base.path, merged.path), env, logger = logger, echo = false)
        val tMerge = System.currentTimeMillis()
        logger.lifecycle("hot symbols: ${s.hotSymbols.readLines().count { it.isNotBlank() }}")
        val exe = File(app, "${info.executable}.kexe")
        Proc.check(
            listOf("python3", "-I", tools.embedMerged.path, base.path, merged.path, exe.path),
            s.llvmHome?.let { mapOf("KONAN_LLVM_HOME" to it) } ?: emptyMap(), logger = logger,
        )

        val bundle = appBundle(info)
        bundle.deleteRecursively()
        File(bundle, "Frameworks").mkdirs()
        exe.copyTo(File(bundle, info.executable))
        File(bundle, info.executable).setExecutable(true)
        File(s.infoPlist).copyTo(File(bundle, "Info.plist"))
        // Compose Multiplatform resources (Res.drawable...): looked up under <bundle>/compose-resources/.
        val resDirs = s.composeResources.map(::File).filter { it.isDirectory }
        resDirs.forEach { res -> res.copyRecursively(File(bundle, "compose-resources"), overwrite = true) }
        resourceManifest.writeText(resourceDigests(resDirs).entries.joinToString("") { (k, v) -> "$v  $k\n" })
        externalKlibManifest.writeText(externalKlibStamps().entries.joinToString("") { (k, v) -> "$v  $k\n" })
        // Every klib the app is linked with: the hot reload modules' own klibs, then the external ones.
        File(work, "linked-klibs.txt").writeText((s.libraries.map { "module  ${s.klibOf(it).path}" } + s.extraKlibs.map { "external  $it" }).joinToString("\n", postfix = "\n"))
        if (resDirs.isNotEmpty()) {
            logger.lifecycle("compose resources: ${resDirs.sumOf { d -> d.walkTopDown().count { it.isFile } }} files from ${resDirs.size} module(s) -> compose-resources/")
        }
        File(s.kaldoDylib).copyTo(File(bundle, "Frameworks/${File(s.kaldoDylib).name}"))
        // The linker leaves absolute rpaths (e.g. to the LLVM install). Outside the bundle the simulator
        // rejects the dylib (codesign invalid-page), so keep only bundle-relative rpaths and re-sign ad hoc.
        val binary = File(bundle, info.executable)
        absoluteRpaths(binary).forEach { rp ->
            Proc.check(listOf("install_name_tool", "-delete_rpath", rp, binary.path), echo = false)
            logger.lifecycle("dropped rpath $rp")
        }
        Proc.check(listOf("codesign", "--force", "--deep", "--sign", "-", bundle.path), echo = false)
        verifyBundle(bundle, info, merged)
        val tEnd = System.currentTimeMillis()
        logger.lifecycle("package: ${bundle.path} (ad-hoc signed)")
        markApplied(t0)
        logger.lifecycle("package timing ms=${tEnd - t0} compile=${tCompile - t0} merge=${tMerge - tCompile} embed+bundle=${tEnd - tMerge}")
    }

    // ---------------------------------------------------------------- reload

    /** Without a classifier baseline: compile and send; no rebuilt object is "nothing changed", not an error. */
    fun reload(daemons: Daemons?) {
        val patch = try {
            preparePatch(daemons)
        } catch (e: NothingChanged) {
            logger.lifecycle(e.message!!.removePrefix("CMP hot reload: "))
            return
        }
        sendPatch(patch)
    }

    /**
     * Sends the patch and waits for the app's answer in its log. Only a patch the app took becomes the applied state;
     * when the app reports `HR reload: NOT applied` (toolchain .8+: its main thread did not take the reload in time),
     * [PatchNotApplied] is thrown and the same objects are sent again by the next run.
     */
    fun sendPatch(merged: File) {
        val log = File(s.launchLog)
        val before = if (log.isFile) log.length() else -1L
        val sentAt = System.currentTimeMillis()
        ReloadSender.send(s.port, listOf(merged.path))
        logger.lifecycle("sent ${merged.path} port ${s.port}")
        if (before >= 0) confirmLoaded(log, before, sentAt)
        markApplied(sentAt)
    }

    /**
     * Toolchain .8+: the runtime applies a reload on the main queue and logs `HR reload: ran on the main queue … waitMs=…
     * runMs=…`, or `HR reload: NOT applied: the main thread did not take the reload within … ms` (then nothing changed
     * in the app). Before .8 (or with KONAN_HR_RELOAD_ON_MAIN=0) only the loader's `[kaldo] ::` lines show up.
     */
    private fun confirmLoaded(log: File, from: Long, t0: Long) {
        // The runtime gives up after mainWaitMs; allow for linking before and for a loaded machine.
        val deadline = t0 + s.mainWaitMs + 6000
        var loadedAt = 0L
        while (System.currentTimeMillis() < deadline) {
            val text = log.inputStream().use { input ->
                input.skip(from)
                input.readBytes().toString(Charsets.UTF_8)
            }
            when (val o = reloadOutcome(text)) {
                is ReloadOutcome.NotApplied -> throw PatchNotApplied(
                    "CMP hot reload: the app was busy, patch not applied: its main thread did not take the reload within " +
                        "${o.waitMs ?: s.mainWaitMs} ms (\"${o.line}\"). Save again or rerun ./gradlew hotReload; the same change is sent again.",
                )
                is ReloadOutcome.Applied -> {
                    logger.lifecycle("app loaded the patch (${System.currentTimeMillis() - t0} ms; on the main queue after " +
                        "${o.waitMs ?: "?"} ms, applied in ${o.runMs ?: "?"} ms); it shows on the next recomposition")
                    return
                }
                ReloadOutcome.Loaded -> {
                    if (loadedAt == 0L) loadedAt = System.currentTimeMillis()
                    // No `HR reload:` line at all (toolchain before .8, or the reload on the listener thread): loaded.
                    if (System.currentTimeMillis() - loadedAt > s.mainWaitMs + 1500) {
                        logger.lifecycle("app loaded the patch (${System.currentTimeMillis() - t0} ms); it shows on the next recomposition")
                        return
                    }
                }
                ReloadOutcome.None -> {}
            }
            Thread.sleep(100)
        }
        if (loadedAt != 0L) {
            logger.lifecycle("app loaded the patch (${System.currentTimeMillis() - t0} ms); it shows on the next recomposition")
            return
        }
        logger.warn("CMP hot reload: no reaction from the app in ${log.path} within ${(deadline - t0) / 1000} s; is it running (./gradlew hotReloadRun) and listening on port ${s.port}?")
    }

    /**
     * The app's loader (libKaldo) logs `[kaldo] ::` lines while it links a patch. Seeing them in the launch log
     * confirms the running app took the patch; the new code then shows on the next recomposition.
     */

    /** Compile (cache-only), collect this run's objects, merge them and check the symbols; returns the object to send. */
    fun preparePatch(daemons: Daemons?): File {
        writeGeneratedMain()
        if (!s.hotSymbols.isFile) throw GradleException("CMP hot reload: no packaged app in ${s.appDir}; run ./gradlew hotReloadPackage first")
        // #7: the sources are byte for byte what the running app was given - nothing to compile or send. (A failed
        // attempt does not update the digest, so the next run still compiles; resource-only changes are judged by
        // the classifier, which then asks for a warm restart.)
        if (appliedSources.isFile && appliedSources.readText() == sourcesDigest()) throw NothingChanged(s.modules.size)
        // Archives are filtered by mtime (second granularity, like the reference driver) against the moment the
        // running app last got new code (package or a sent patch): what a failed attempt already compiled is
        // still sent by the next one.
        val start = (if (appliedStamp.isFile) appliedStamp.lastModified() else System.currentTimeMillis()) / 1000 * 1000
        val caches = File(work, "caches.txt").apply { delete() }
        val patchExe = File(s.appDir, "${appInfo().executable}-patch.kexe")
        compile(programArgs(patchExe, caches), if (daemons != null) s.compileSocket else null, "compile", cacheOnly = true, daemons = daemons)
        if (!caches.isFile) throw GradleException("CMP hot reload: compiler wrote no ${caches.name}")
        // Caches built since the app last got new code, including by attempts that failed after compiling:
        // the compiler lists only what it built in this run, so the list is carried until a patch is sent.
        val archives = pendingArchives(pendingCaches.takeIf { it.isFile }?.readLines() ?: emptyList(), caches.readLines())
        pendingCaches.writeText(archives.joinToString("\n"))

        val patchDir = File(work, "patch").apply { deleteRecursively(); mkdirs() }
        val modulePackages = s.modules.associateWith { packagesOf(it) }
        val objects = linkedMapOf<HrModule, MutableList<File>>()
        archives.forEachIndexed { i, a ->
            val archive = File(a)
            if (archive.lastModified() < start) {
                logger.info("skip stale: $a")
                return@forEachIndexed
            }
            val dir = File(patchDir, "a$i-${archive.nameWithoutExtension}").apply { mkdirs() }
            Proc.check(listOf("ar", "x", archive.path), cwd = dir, echo = false)
            dir.listFiles { f -> f.name.endsWith(".o") }!!.sorted().forEach { o ->
                // One module (e.g. the KMP template's `shared`): every rebuilt object is its own.
                val m = moduleOf(s.modules, modulePackages) { definedSymbols(o) }
                    ?: throw GradleException("CMP hot reload: cannot tell which module ${o.path} belongs to (no Kotlin symbol of a known package)")
                objects.getOrPut(m) { mutableListOf() } += o
            }
        }
        if (objects.isEmpty()) throw NothingChanged(s.modules.size)

        val ordered = s.modules.filter { it in objects }
        logger.lifecycle("changed objects: " + (listOf(s.app) + s.libraries).joinToString(" ") { "${it.label}=${objects[it]?.size ?: 0}" })
        val all = ordered.flatMap { objects.getValue(it) }
        val merged = if (all.size == 1) all.single() else File(patchDir, "merged.o").also {
            Proc.check(listOf("ld", "-r") + all.map { f -> f.path } + listOf("-o", it.path), echo = false)
        }
        val mergedSyms = definedSymbols(merged)
        for (m in ordered) {
            val defined = objects.getValue(m).flatMap { definedSymbols(it) }.toSet()
            val kept = defined.count { it in mergedSyms }
            logger.lifecycle("symbols: ${m.label} defined=${defined.size} kept_in_merged=$kept")
            if (defined.isEmpty() || kept != defined.size) {
                throw GradleException("CMP hot reload: symbol check failed for ${m.label} (defined=${defined.size}, kept=$kept); not sending")
            }
        }
        return merged
    }

    private fun absoluteRpaths(binary: File): List<String> =
        rpaths(Proc.check(listOf("otool", "-l", binary.path), echo = false)).filter { it.startsWith("/") }

    /**
     * Checks the packager duties of INTERFACE §3.3 on the finished bundle; any miss fails the build:
     * #1 compact unwind kept (merged object has __KALDO,__kaldo_cu), #3 a non-empty hot-symbol list,
     * #5 libKaldo inside Frameworks with the manifest's sha256 and no absolute rpath, #6 a valid signature.
     */
    fun verifyBundle(bundle: File, info: AppInfo, merged: File) {
        val problems = mutableListOf<String>()
        if (s.keepCompactUnwind) {
            val sections = Proc.check(listOf("otool", "-l", merged.path), echo = false)
            if (!sections.contains("sectname __kaldo_cu")) problems += "#1 merged object has no __kaldo_cu (compact unwind not kept)"
        }
        if (s.hotSymbols.readLines().none { it.isNotBlank() }) problems += "#3 hot-symbol list ${s.hotSymbols.path} is empty"
        val lib = File(bundle, "Frameworks/${File(s.kaldoDylib).name}")
        if (!lib.isFile) problems += "#5 ${lib.path} missing"
        else if (s.kaldoSha256 != null && Tools.sha256(lib.readBytes()) != s.kaldoSha256) problems += "#5 bundled libKaldo differs from the manifest's kaldoIossimSha256"
        val rp = rpaths(Proc.check(listOf("otool", "-l", File(bundle, info.executable).path), echo = false))
        rp.filter { it.startsWith("/") }.forEach { problems += "#5 absolute rpath left: $it" }
        if (rp.none { it.startsWith("@executable_path") }) problems += "#5 no @executable_path rpath; libKaldo would not be found"
        val sig = Proc.run(listOf("codesign", "--verify", "--deep", bundle.path), echo = false)
        if (sig.exit != 0) problems += "#6 codesign --verify failed: ${sig.output.trim()}"
        if (problems.isNotEmpty()) throw GradleException("CMP hot reload: bundle violates INTERFACE §3.3:\n" + problems.joinToString("\n") { "  - $it" })
        logger.lifecycle("bundle check (INTERFACE §3.3): compact unwind ${if (s.keepCompactUnwind) "kept" else "off"}, " +
            "hot symbols ${s.hotSymbols.readLines().count { it.isNotBlank() }}, libKaldo sha256 ok, rpaths $rp, signature valid")
    }

    companion object {
        /** Minimal UIKit app around a function returning the root UIViewController (KMP template layout). */
        fun generatedMainSource(entry: String, executable: String): String = """
            |// Generated by com.solufee.cmp.hotreload (cmpHotReload { app { iosEntry = "$entry" } }). Do not edit.
            |@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
            |
            |import kotlinx.cinterop.*
            |import platform.Foundation.NSStringFromClass
            |import platform.UIKit.*
            |
            |class HotReloadAppDelegate : UIResponder, UIApplicationDelegateProtocol {
            |    companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta {}
            |
            |    @OverrideInit
            |    constructor() : super()
            |
            |    private var keyWindow: UIWindow? = null
            |    override fun window() = keyWindow
            |    override fun setWindow(window: UIWindow?) { keyWindow = window }
            |
            |    override fun application(application: UIApplication, didFinishLaunchingWithOptions: Map<Any?, *>?): Boolean {
            |        val w = UIWindow(frame = UIScreen.mainScreen.bounds)
            |        w.rootViewController = $entry()
            |        w.makeKeyAndVisible()
            |        keyWindow = w
            |        return true
            |    }
            |}
            |
            |fun main(args: Array<String>) {
            |    println("HR entry: $entry")
            |    memScoped {
            |        val argv = (arrayOf("$executable") + args).map { it.cstr.ptr }.toCValues()
            |        autoreleasepool {
            |            UIApplicationMain(args.size + 1, argv, null, NSStringFromClass(HotReloadAppDelegate))
            |        }
            |    }
            |}
            |""".trimMargin()

        fun externalKlibs(lines: List<String>, dist: File): List<String> {
            val implicit = File(dist, "klib").canonicalPath + "/"
            return lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .filterNot { File(it).canonicalPath.startsWith(implicit) }
        }

        /** LC_RPATH entries from `otool -l` output. */
        fun rpaths(otoolL: String): List<String> {
            val lines = otoolL.lines()
            return lines.withIndex().filter { it.value.trim() == "cmd LC_RPATH" }.mapNotNull { (i, _) ->
                lines.drop(i + 1).take(3).firstOrNull { it.trim().startsWith("path ") }
                    ?.trim()?.removePrefix("path ")?.substringBefore(" (offset")
            }
        }

        private val PACKAGE = Regex("""^\s*package\s+([A-Za-z_][A-Za-z0-9_.`]*)""", RegexOption.MULTILINE)

        fun sourcePackages(dir: File): Set<String> =
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .map { f -> PACKAGE.find(f.readText())?.groupValues?.get(1)?.replace("`", "") ?: "" }
                .toSet()

        fun definedSymbols(o: File): Set<String> =
            Proc.check(listOf("nm", "-g", "-U", o.path), echo = false).lines()
                .mapNotNull { l -> l.trim().split(Regex("\\s+")).takeIf { it.size >= 3 && it[1] != "U" }?.get(2) }
                .toSet()

        /**
         * Module of an object: vote with every `_kfun:<package>...` symbol for the module that declares the
         * longest matching package; symbols of the root package (`_kfun:#`) vote for the module declaring "".
         */
        private val KOTLIN_SYMBOL_PREFIXES = listOf("_kfun:", "_kclass:", "_kvar:")

        /**
         * Per-file cache archives to look at for a patch: those still pending from attempts that failed after
         * compiling (the compiler lists only what it built in this run) plus this run's, de-duplicated.
         * The list is cleared when a patch is sent or the app is packaged.
         */
        /** Why a klib is compiled: "first build", or what differs between two [libraryInputs] (#21). */
        fun klibChangeReason(before: List<String>?, now: List<String>): String {
            if (before == null) return "no previous build"
            fun keyed(lines: List<String>) = lines.associate { l -> l.substringBeforeLast(' ') to l.substringAfterLast(' ') }
            val a = keyed(before)
            val b = keyed(now)
            val changed = (a.keys + b.keys).filter { a[it] != b[it] }.sorted()
            fun describe(kind: String, label: String): String? {
                val keys = changed.filter { it.startsWith("$kind ") }.map { it.removePrefix("$kind ") }
                if (keys.isEmpty()) return null
                val names = keys.map { if (kind == "source" || kind == "external") File(it).name else it }
                return "$label: ${names.take(3).joinToString()}" + (if (names.size > 3) " and ${names.size - 3} more" else "")
            }
            return listOfNotNull(
                describe("source", "sources changed"),
                describe("module", "module klibs changed"),
                describe("external", "dependency klibs changed"),
                if (changed.any { it.startsWith("args ") || it.startsWith("toolchain ") }) "compiler arguments or toolchain changed" else null,
            ).joinToString("; ").ifEmpty { "inputs changed" }
        }

        fun pendingArchives(pending: List<String>, built: List<String>): List<String> =
            (pending + built).map { it.trim() }.filter { it.contains("per-file-cache/") && it.endsWith(".a") }.distinct()

        /** Relative path -> sha256 of every file under [dirs] (Compose resources of all modules). */
        fun resourceDigests(dirs: List<File>): Map<String, String> = dirs.flatMap { d ->
            d.walkTopDown().filter { it.isFile }.map { it.relativeTo(d).path to Tools.sha256(it.readBytes()) }.toList()
        }.toMap().toSortedMap()

        /** Module of a rebuilt object: with one module (the KMP template's `shared`) every object is its own. */
        fun moduleOf(modules: List<HrModule>, packages: Map<HrModule, Set<String>>, symbols: () -> Set<String>): HrModule? =
            if (modules.size == 1) modules.single() else classify(symbols(), packages)

        fun classify(symbols: Set<String>, packages: Map<HrModule, Set<String>>): HrModule? {
            val votes = mutableMapOf<HrModule, Int>()
            for (sym in symbols) {
                // Functions, classes and globals carry the package after their prefix; file initializers
                // (`__Konan_init_<hash>:<package>...`) after the colon.
                val rest = KOTLIN_SYMBOL_PREFIXES.firstOrNull { sym.startsWith(it) }?.let { sym.removePrefix(it) }
                    ?: sym.takeIf { it.startsWith("__Konan_init_") && ':' in it }?.substringAfter(':')
                    ?: continue
                var best: HrModule? = null
                var bestLen = -1
                for ((m, pkgs) in packages) for (p in pkgs) {
                    val hit = if (p.isEmpty()) rest.startsWith("#") else rest.startsWith("$p#") || rest.startsWith("$p.")
                    if (hit && p.length > bestLen) {
                        best = m
                        bestLen = p.length
                    }
                }
                // Root-package classes (`_kfun:Foo#bar`) match no named package: they belong to the root-package module.
                if (best == null) best = packages.entries.firstOrNull { "" in it.value }?.key
                if (best != null) votes[best] = (votes[best] ?: 0) + 1
            }
            return votes.maxByOrNull { it.value }?.key
        }
    }
}
