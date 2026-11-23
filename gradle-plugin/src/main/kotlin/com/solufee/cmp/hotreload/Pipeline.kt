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

/**
 * Issue #91: the patch cannot be linked the way the packaged app was, for the reasons in [reasons] (`WARM <category> ...`
 * lines, e.g. it uses a name the packaged app keeps only as local copies); the hotReload task restarts warm.
 */
class NeedsWarm(val reasons: List<String>) : GradleException("CMP hot reload: this patch needs a warm restart:\n" + reasons.joinToString("\n"))

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

/** A direct compiler run failed; [output] is what it printed (already echoed to the build log). */
class CompileFailed(message: String, val output: String) : GradleException(message)

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
    fun externalKlibs(): List<String> = toolchainKlibs() + s.extraKlibs

    /** The toolchain's klibs that are linked: the list minus those replaced by the project's newer copies (P0/B1). */
    fun toolchainKlibs(): List<String> {
        val dropped = s.droppedToolchainKlibs.map { File(it).canonicalPath }.toSet()
        return externalKlibs(File(s.klibList).readLines(), File(s.dist)).filter { File(it).canonicalPath !in dropped }
    }


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
        val home = File(System.getProperty("user.home")).canonicalPath
        // A root above the project would cache the project's own module klibs as whole libraries (they are then hot
        // symbols in a library cache, and the duplicate-definition tiers fail, P0 C): the same guard for the
        // toolchain's klibs (a klib list edited to point outside the toolchain directory) as for the dependencies.
        fun rootsFor(klibs: List<String>): List<String> {
            val common = autoCacheFrom(klibs)
            val tooBroad = common == "/" || File(common).canonicalPath == home || File(s.rootDir).canonicalPath.startsWith(File(common).canonicalPath + "/")
            return if (tooBroad) klibs.map { File(it).parent }.distinct() else listOf(common)
        }
        val toolchain = toolchainKlibs()
        val roots = mutableListOf<String>()
        roots += if (toolchain.isEmpty()) listOf(autoCacheFrom(toolchain)) else rootsFor(toolchain)
        if (s.extraKlibs.isNotEmpty()) roots += rootsFor(s.extraKlibs)
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
        GeneratedDeclarations.record(s)
        PluginAnnotations.record(s)
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

    /**
     * #29/#47: Compose compiler options of every hot-reload compile (library klibs, package, patch, the classifier's app
     * klib). Release compiles (ReleaseCheck) never use them.
     */
    val composeOptions: List<String> by lazy {
        if (s.stableKeys && composePluginKnows(File(s.composePlugin), STABLE_KEYS)) listOf("-P", STABLE_KEYS_OPTION) else emptyList()
    }

    /** #76: the project's own compiler plugins of [m] (not Compose) and their options, as the normal build passes them. */
    fun projectPluginArgs(m: HrModule): List<String> =
        m.compilerPlugins.map { "-Xplugin=$it" } + m.pluginOptions.flatMap { listOf("-P", it) }

    /** "on", "off" or "unsupported" (the toolchain's Compose compiler does not know the flag), for messages and the package record. */
    val stableKeysState: String get() = when {
        composeOptions.isNotEmpty() -> "on"
        !s.stableKeys -> "off"
        else -> "unsupported"
    }

    fun libraryArgs(m: HrModule, out: File = s.klibOf(m)): List<String> {
        val base = listOf("-target", s.target, "-p", "library", "-g", "-Xplugin=${s.composePlugin}") + composeOptions + projectPluginArgs(m) +
            listOf("-opt-in=kotlinx.cinterop.ExperimentalForeignApi")
        val sources = sourceArgs(m)
        return base + projectCompilerArgs(m, base + sources) + klibLinks(m) + sources + listOf("-o", out.path.removeSuffix(".klib"))
    }

    /**
     * #133: the module's own compiler settings ([HrModule.compilerArgs]) without what [base] (this compile's plugin-owned
     * arguments) already has; the language/API versions are checked against the toolchain's compiler first.
     */
    fun projectCompilerArgs(m: HrModule, base: List<String>): List<String> {
        if (m.compilerArgs.isEmpty()) return emptyList()
        checkLanguageVersions
        return CompilerArgs.without(m.compilerArgs, base)
    }

    /** #133: every module's -language-version / -api-version against the versions the toolchain's compiler knows (once). */
    private val checkLanguageVersions: Unit by lazy {
        val known = CompilerArgs.knownLanguageVersions(File(s.compilerJar))
        val problems = s.modules.flatMap { m -> CompilerArgs.unsupportedVersions(m.compilerArgs, known, compilerVersion()).map { "${m.path}: $it" } }
        if (problems.isNotEmpty()) throw GradleException("CMP hot reload: " + problems.joinToString("\n"))
    }

    /**
     * #52 (#32, toolchain .9+): the package compile writes the bootstrap payload paths to a list instead of copying
     * ~100-200 MB of payloads into the base executable; merge-payloads reads them from disk (KALDO_PAYLOAD_LIST).
     * Only when the toolchain's compiler knows the binary option; release compiles never use it.
     */
    val splitPayloads: Boolean by lazy { compilerKnowsSplitPayloadList(File(s.compilerJar)) }

    /** Identical for package and reload, so the incremental caches are shared (only -o, the dump and [packageOnly] differ). */
    fun programArgs(output: File, dumpCaches: File?, packageOnly: List<String> = emptyList()): List<String> {
        return listOf(
            "-target", s.target, "-p", "program", "-g",
            "-Xcompilation-scheme=split",
            "-Xenable-incremental-compilation", "-Xic-cache-dir=${File(work, "ic").path}",
            "-Xbinary=perFileCacheForStdlib=false",
            *autoCacheRoots().map { "-Xauto-cache-from=$it" }.toTypedArray(),
            "-Xauto-cache-dir=${File(work, "auto-cache").path}",
            "-Xplugin=${s.composePlugin}",
            *composeOptions.toTypedArray(),
            *projectPluginArgs(s.app).toTypedArray(),
            "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
        ).let { base -> base + projectCompilerArgs(s.app, base + sourceArgs(s.app)) } + s.linkerOptions.map { "-linker-option=$it" } +
            listOf("-linker-option=-force_load", "-linker-option=${s.objcUtilsLib}") +
            klibLinks(null) +
            (dumpCaches?.let { listOf("-Xdump-built-caches-to=${it.path}") } ?: emptyList()) +
            packageOnly + listOf("-o", output.path) + sourceArgs(s.app)
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
        try {
            compileOnce(args, daemonSocket, kind, cacheOnly, daemons, retryDirectOnError)
        } catch (e: GradleException) {
            // #76: a project compiler plugin built for another compiler version fails inside the compiler; name it.
            val jars = args.filter { it.startsWith("-Xplugin=") && it != "-Xplugin=${s.composePlugin}" }.map { File(it.removePrefix("-Xplugin=")) }
            val hint = pluginIncompatibility((e.message ?: "") + "\n" + ((e as? CompileFailed)?.output ?: ""), jars, compilerVersion(), s.javaHome) ?: throw e
            throw GradleException(hint + "\n" + (e.message ?: ""), e)
        }
    }

    private fun compilerVersion(): String =
        File(s.dist, TOOLCHAIN_MANIFEST).takeIf { it.isFile }?.let { runCatching { Toolchain.parse(it).kotlinVersion }.getOrNull() } ?: "of the toolchain"

    private fun compileOnce(
        args: List<String>, daemonSocket: String?, kind: String, cacheOnly: Boolean, daemons: Daemons?,
        retryDirectOnError: Boolean,
    ) {
        // the full argument list of every hot reload compile (--info), to compare with the normal build's compile
        logger.info("compile args ($kind${if (cacheOnly) ", cache only" else ""}): " + args.joinToString(" "))
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
        // The output is echoed as before; it also travels with the failure so a plugin incompatibility can be named (#76).
        val cmd = listOf(s.kotlinc) + args
        // #120: a temp directory of its own, removed when the compiler has exited (it unpacks included<digits> there)
        val tmp = File(work, "tmp/direct-${ProcessHandle.current().pid()}-${System.nanoTime()}")
        Daemons.ensurePrivateDir(tmp)
        val r = try {
            Proc.run(cmd, env = compileEnvironment(cacheOnly, tmp), logger = logger)
        } finally {
            tmp.deleteRecursively()
        }
        if (r.exit != 0) throw CompileFailed("CMP hot reload: `${cmd.joinToString(" ").take(300)}` failed (exit ${r.exit})", r.output)
    }

    /**
     * Environment of a direct compile. INTERFACE §3.3 #4: the package (host link) compile must not run with
     * KONAN_HOT_RELOAD_CACHE_ONLY; Proc also scrubs it from the caller's environment. #120: with [tmp], the compiler JVM
     * (JAVA_OPTS, read by kotlinc-native's run_konan) and the tools it starts (TMPDIR) use that directory.
     */
    fun compileEnvironment(cacheOnly: Boolean, tmp: File? = null): Map<String, String> =
        mapOf("JAVA_HOME" to s.javaHome) + (if (cacheOnly) mapOf("KONAN_HOT_RELOAD_CACHE_ONLY" to "1") else emptyMap()) +
            (if (tmp != null) mapOf("TMPDIR" to tmp.path,
                "JAVA_OPTS" to listOfNotNull(System.getenv("JAVA_OPTS")?.takeIf { it.isNotBlank() }, "-Djava.io.tmpdir=${tmp.path}").joinToString(" ")) else emptyMap())

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
     * module (generated ones included) by path and sha256, each module klib it links by its ABI fingerprint (#124,
     * [abiFingerprint]: a body-only edit upstream does not rebuild the dependents) or, when that is off or cannot be
     * computed, by sha256 of its bytes (klibs are deterministic, so an unchanged upstream rebuild does not rebuild them),
     * and each dependency klib from Gradle by size and mtime. The toolchain's own klibs are covered by the arguments
     * (paths of the pinned toolchain) and the toolchain identity.
     */
    fun libraryInputs(m: HrModule, out: File = s.klibOf(m)): List<String> {
        val lines = mutableListOf(
            "args - ${Tools.sha256(libraryArgs(m, out).joinToString("\u0000").toByteArray())}",
            // #98: the build identity (toolchain contents, plugin version, compiler plugins), not only the toolchain's paths
            "toolchain - ${File(work, BuildIdentity.FILE).takeIf { it.isFile }?.let { BuildIdentity.digest(it.readLines().filter { l -> l.isNotBlank() }) }
                ?: Tools.sha256("${s.compilerJar}|${s.composePlugin}|${s.klibList}".toByteArray())}",
        )
        m.sourceDirs.map(::File).filter { it.isDirectory }.forEach { d ->
            d.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f -> lines += "source ${f.path} ${Tools.sha256(f.readBytes())}" }
        }
        s.libraries.filter { it.path in m.deps }.forEach { d ->
            val k = s.klibOf(d)
            lines += "module ${d.label} " + if (!k.isFile) "missing" else {
                val sha = Tools.sha256(k.readBytes())
                (if (s.abiFingerprint) abiFingerprint(k, sha) else null)?.let { "abi $it" } ?: "sha256 $sha"
            }
        }
        externalKlibStamps().forEach { (path, stamp) -> lines += "external $path $stamp" }
        return lines.sorted()
    }

    /** #124: tools for [abiFingerprint] (extracted from the plugin jar on first use). */
    private val fingerprintTools: Tools by lazy { Tools.extract(work, null) }

    /** #124: runs `compat-classify.py abi-fingerprint`; replaced in tests. Returns its output (exit 0) or throws. */
    internal var abiFingerprintCommand: (File) -> String = { klib ->
        val cmd = listOf("python3", "-I", fingerprintTools.compatClassify.path, "abi-fingerprint", "--klib", klib.path,
            "--klib-tool", s.klibTool, "--cache-dir", File(work, "abi-cache").path) +
            (if (File(s.klibSocket).exists()) listOf("--daemon", s.klibSocket) else emptyList())
        Proc.check(cmd, mapOf("JAVA_HOME" to s.javaHome), echo = false)
    }

    /**
     * #124: what modules downstream of [klib] depend on (sha256 [sha]): its metadata dump (every declaration, private
     * ones included, annotations, const values, Compose's stability annotations) and the IR of its inline functions;
     * the bodies of non-inline functions are not part of it. Remembered next to the klib per [sha]. Null when it cannot be
     * computed (the caller then uses [sha]: a failure only costs the downstream recompiles it would have saved).
     */
    fun abiFingerprint(klib: File, sha: String): String? {
        val memo = File(klib.parentFile, klib.name + ".abi")
        memo.takeIf { it.isFile }?.readText()?.trim()?.split(' ')?.takeIf { it.size == 2 && it[0] == sha }?.let { return it[1] }
        return try {
            val fp = abiFingerprintCommand(klib).trim().lines().last().trim()
            if (!ABI_FINGERPRINT.matches(fp)) throw IllegalStateException("unexpected output: ${fp.take(80)}")
            memo.writeText("$sha $fp\n")
            fp
        } catch (e: Exception) {
            logger.warn("CMP hot reload: ABI fingerprint of ${klib.name} failed (${e.message?.lineSequence()?.firstOrNull()?.take(200)}); " +
                "its dependents compare its klib bytes instead")
            memo.delete()
            null
        }
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

    /** #129: where a module's last [libraryStamp] is kept (next to its klib and input record). */
    fun klibStampFile(m: HrModule): File = File(s.klibOf(m).parentFile, "stamp.txt")

    /**
     * #129: what the reload's library pass found: modules skipped by their stamp, checked by their inputs (unchanged),
     * compiled. The order is the compile order.
     */
    data class LibraryPass(val skipped: List<String>, val checked: List<String>, val compiled: List<String>, val ms: Long)

    /** #129: the library modules in dependency order (each after the library modules it links). */
    fun libraryOrder(): List<HrModule> {
        val byPath = s.libraries.associateBy { it.path }
        val done = linkedSetOf<String>()
        fun visit(m: HrModule, stack: Set<String>) {
            if (m.path in done) return
            if (m.path in stack) throw GradleException("CMP hot reload: library modules depend on each other in a cycle: ${(stack + m.path).joinToString(" -> ")}")
            m.deps.mapNotNull { byPath[it] }.forEach { visit(it, stack + m.path) }
            done += m.path
        }
        s.libraries.forEach { visit(it, emptySet()) }
        return done.map { byPath.getValue(it) }
    }

    /**
     * #129: the reload's own library pass (instead of one Gradle task per module). Each module, in dependency order, is
     * - skipped when its [libraryStamp] equals the one recorded after its last check or compile, its klib and input record
     *   exist, and no module it links was compiled in this pass: then nothing it is compiled from can have changed;
     * - otherwise handed to the same check as the per-module task ([compileKlibIfChanged]: sources hashed, upstream
     *   modules by ABI fingerprint (#124)), which compiles it only when its inputs differ.
     * After each check or compile the stamp is recorded again.
     */
    fun compileLibrariesInReload(daemons: Daemons?): LibraryPass {
        val t0 = System.currentTimeMillis()
        val global = globalStamp()
        val skipped = mutableListOf<String>(); val checked = mutableListOf<String>(); val compiled = mutableSetOf<String>()
        for (m in libraryOrder()) {
            val stampFile = klibStampFile(m)
            val upstreamCompiled = m.deps.any { it in compiled }
            val stamp = global + libraryStamp(m)
            if (canSkip(m, stamp, upstreamCompiled)) {
                skipped += m.label
                continue
            }
            if (compileKlibIfChanged(m, s.klibOf(m), klibInputsFile(m), "klib ${m.label}", daemons)) {
                logger.lifecycle("klib ${m.label}: ${s.klibOf(m).path}")
                compiled += m.path
            } else {
                checked += m.label
            }
            stampFile.writeText((global + libraryStamp(m)).joinToString("\n", postfix = "\n"))
        }
        val pass = LibraryPass(skipped, checked, s.libraries.filter { it.path in compiled }.map { it.label }, System.currentTimeMillis() - t0)
        logger.lifecycle("klibs: ${pass.compiled.size} compiled, ${pass.checked.size} checked and unchanged, ${pass.skipped.size} unchanged by file metadata " +
            "(${pass.ms} ms)" + (if (pass.skipped.isEmpty()) "" else "; skipped: ${pass.skipped.joinToString()}"))
        return pass
    }

    /** #129: whether [m] can be skipped: [stamp] equals the recorded one, klib and input record exist, nothing upstream compiled. */
    fun canSkip(m: HrModule, stamp: List<String>, upstreamCompiled: Boolean): Boolean {
        val f = klibStampFile(m)
        return !upstreamCompiled && s.klibOf(m).isFile && klibInputsFile(m).isFile && f.isFile &&
            f.readLines().filter { it.isNotBlank() } == stamp
    }

    /**
     * #129: metadata of everything a module's klib is compiled from, without reading any file's contents: the compiler
     * arguments (they carry the options and plugins from the build scripts, the linked klibs' paths and the source list),
     * every file under its source directories (generated ones included) and its build files, its own klib and input record,
     * and the klibs and input records of the library modules it links. A file is stamped by path, size, mtime, ctime and
     * inode: a tool that sets mtime back (touch -r, rsync -t, "keep timestamps") still changes ctime.
     */
    fun libraryStamp(m: HrModule): List<String> {
        val lines = mutableListOf("args ${Tools.sha256(libraryArgs(m).joinToString("\u0000").toByteArray())}")
        m.sourceDirs.map(::File).forEach { d ->
            if (!d.exists()) lines += "dir ${d.path} missing"
            else d.walkTopDown().filter { it.isFile }.forEach { f -> lines += "src " + fileStamp(f) }
        }
        (s.moduleBuildFiles[m.path] ?: emptyList()).forEach { lines += "build " + fileStamp(File(it)) }
        lines += "klib " + fileStamp(s.klibOf(m))
        lines += "record " + fileStamp(klibInputsFile(m))
        s.libraries.filter { it.path in m.deps }.forEach { d ->
            lines += "dep-klib " + fileStamp(s.klibOf(d))
            lines += "dep-record " + fileStamp(klibInputsFile(d))
        }
        return lines.sorted()
    }

    /**
     * #129: metadata of what every module's compile depends on beyond its own files: the build identity (#98), the
     * build's settings, properties and version catalogs (root and included builds), and the external klibs (from Gradle;
     * a directory klib by every file in it).
     */
    fun globalStamp(): List<String> {
        val lines = mutableListOf("identity " + fileStamp(File(work, BuildIdentity.FILE)))
        s.globalBuildFiles.forEach { lines += "global " + fileStamp(File(it)) }
        s.extraKlibs.map(::File).forEach { f ->
            if (f.isDirectory) f.walkTopDown().filter { it.isFile }.forEach { lines += "external " + fileStamp(it) }
            else lines += "external " + fileStamp(f)
        }
        return lines.sorted()
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
     * #93: what hotReloadRun needs from a package: the .app, its hot symbol list and, when the app loads its library object
     * from a file (#56), that file. False after hotReloadClean or before the first package.
     */
    fun packagedAppReady(): Boolean {
        if (!appBundle().isDirectory || !s.hotSymbols.isFile) return false
        val payload = packagedPayloadFile.takeIf { it.isFile }?.readText()?.trim()
        return payload.isNullOrEmpty() || File(payload).isFile
    }

    /**
     * [daemons]: compile through the klib daemon (it never runs cache-only, INTERFACE §5), falling back to
     * kotlinc-native when it is unreachable or fails.
     */
    /**
     * [beside] runs in the background after the compile, next to merge/embed/bundle (the classifier baseline needs only
     * the module klibs and the app sources, #32/#52); its failure fails the package.
     */
    /**
     * #56 (#32 step 2, toolchain .10+): the cached library object is a second payload ([libsSplit], when the compiler
     * knows `splitPayloadReserve`), loaded by libKaldo from its file when libKaldo knows manifest kind 2 and
     * `libsInExecutable` is off ([libsFromFile]); the reserve in the executable is sized from the last merge.
     */
    val reserveSupported: Boolean by lazy { compilerKnowsBinaryOption(File(s.compilerJar), "splitPayloadReserve") }
    val libsSplit: Boolean get() = splitPayloads && reserveSupported
    val libsFromFileSupported: Boolean by lazy { kaldoLoadsPayloadFiles(File(s.kaldoDylib)) }
    val libsFromFile: Boolean get() = libsSplit && libsFromFileSupported && !s.libsInExecutable

    /** The cached library object merge-payloads writes next to the merged user object (#32 step 2). */
    fun libsObject(merged: File): File = File(merged.parentFile, merged.nameWithoutExtension + ".libs.o")

    /** #56: the payload file the packaged .app loads at start (absent when everything is embedded). */
    val packagedPayloadFile get() = File(work, "payload-file.txt")

    fun packageApp(tools: Tools, daemons: Daemons? = null, beside: (() -> Unit)? = null, fullReserve: Boolean = false) {
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
        logger.lifecycle("stable keys (remember kept across signature changes, #29): $stableKeysState")
        val payloadList = File(app, "payloads.txt").apply { delete() }
        val merged = File(app, "merged-bootstrap.o")
        val libsObj = libsObject(merged)
        // #56: reserve = library object (4 KB when it is loaded from its file) + 4x the last user object + 16 MB, as
        // hotreload.sh; without a previous merge, or after a payload outgrew it ([fullReserve]), the compiler's full reserve.
        val reserve = if (libsSplit && !fullReserve && merged.isFile && libsObj.isFile) {
            (if (libsFromFile) 4096L else libsObj.length()) + 4 * merged.length() + 16L * 1024 * 1024
        } else null
        val splitArgs = (if (splitPayloads) listOf("-Xbinary=splitPayloadList=${payloadList.path}") else emptyList()) +
            (reserve?.let { listOf("-Xbinary=splitPayloadReserve=$it") } ?: emptyList())
        compile(programArgs(base, null, splitArgs), if (daemons != null) s.klibSocket else null, "klib", cacheOnly = false, daemons = daemons,
            retryDirectOnError = true)
        if (!base.isFile) throw GradleException("CMP hot reload: the package compile reported success but ${base.path} is missing")
        val tCompile = System.currentTimeMillis()
        val pending = beside?.let { java.util.concurrent.CompletableFuture.runAsync(it) }
        // #56: drop the absolute rpaths (see below) on a clone of the base executable while the merge runs; embed uses it.
        val embedBase = File(app, "${info.executable}-base.norpath.kexe").apply { delete() }
        if (Proc.run(listOf("cp", "-c", base.path, embedBase.path), echo = false).exit != 0) base.copyTo(embedBase, overwrite = true)
        val rpathJob = java.util.concurrent.CompletableFuture.runAsync {
            absoluteRpaths(embedBase).forEach { rp -> Proc.check(listOf("install_name_tool", "-delete_rpath", rp, embedBase.path), echo = false) }
        }

        val packages = hotPackages()
        val usePayloadList = payloadList.isFile && payloadList.length() > 0
        logger.lifecycle("payload list (#32): " + when {
            usePayloadList -> "on (${payloadList.readLines().count { it.isNotBlank() }} payloads read from disk)"
            splitPayloads -> "requested but not written; payloads embedded"
            else -> "not supported by this toolchain; payloads embedded"
        })
        logger.lifecycle("library payload (#56): " + when {
            !(usePayloadList && libsSplit) -> "in the merged object (toolchain before .10)"
            libsFromFile -> "loaded from its file by libKaldo (this .app needs ${libsObj.path} on this Mac; libsInExecutable = true embeds it)"
            s.libsInExecutable -> "embedded in the executable (libsInExecutable)"
            else -> "embedded in the executable (libKaldo cannot load payload files)"
        } + "; reserve " + (reserve?.let { "${it / (1024 * 1024)} MB from the last merge" } ?: "full"))
        val env = mergeEnvironment(packages) + (if (usePayloadList) mapOf("KALDO_PAYLOAD_LIST" to payloadList.path) else emptyMap()) +
            (if (usePayloadList && libsSplit) mapOf("KALDO_LIB_PAYLOAD_DIRS" to File(work, "auto-cache").path) else emptyMap())
        logger.lifecycle("hot packages: ${packages.joinToString(",").ifEmpty { "<none>" }}; keep compact unwind: ${s.keepCompactUnwind}")
        val mergeOut = Proc.check(listOf("python3", "-I", tools.mergePayloads.path, base.path, merged.path), env, logger = logger, echo = false)
        // Issue #91: the three tiers for names that several libraries define, with every symbol and the reason.
        mergeOut.lines().filter { it.startsWith("duplicate definitions") || it.startsWith("  tier") || it.startsWith("    ") }
            .forEach { logger.lifecycle(it) }
        val tMerge = System.currentTimeMillis()
        logger.lifecycle("hot symbols: ${s.hotSymbols.readLines().count { it.isNotBlank() }}")
        val exe = File(app, "${info.executable}.kexe")
        exe.delete()
        rpathJob.get()
        val libsEntry = if (usePayloadList && libsSplit && libsObj.isFile) listOf(if (libsFromFile) "@file:${libsObj.path}" else libsObj.path) else emptyList()
        val embed = Proc.run(
            listOf("python3", "-I", tools.embedMerged.path, embedBase.path, merged.path) + libsEntry + exe.path,
            s.llvmHome?.let { mapOf("KONAN_LLVM_HOME" to it) } ?: emptyMap(), echo = false,
        )
        if (embed.exit != 0) {
            if (reserve != null) {
                logger.lifecycle("package: the merged payload outgrew the reserve; packaging again with the full reserve")
                runCatching { pending?.get() }
                return packageApp(tools, daemons, beside, fullReserve = true)
            }
            throw GradleException("CMP hot reload: embed-merged.py failed (exit ${embed.exit}):\n${embed.output.trim().takeLast(3000)}")
        }
        logger.lifecycle(embed.output.trim().lines().lastOrNull { it.isNotBlank() } ?: "embedded")
        val tEmbed = System.currentTimeMillis()

        val bundle = appBundle(info)
        bundle.deleteRecursively()
        File(bundle, "Frameworks").mkdirs()
        // clonefile on APFS (no copy of the large executable); a plain copy elsewhere.
        if (Proc.run(listOf("cp", "-c", exe.path, File(bundle, info.executable).path), echo = false).exit != 0) {
            exe.copyTo(File(bundle, info.executable), overwrite = true)
        }
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
        verifyBundle(bundle, info, merged, if (libsFromFile && libsEntry.isNotEmpty()) libsObj else null)
        val tBundle = System.currentTimeMillis()
        try {
            pending?.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? GradleException) ?: GradleException("CMP hot reload: ${e.cause}", e.cause)
        }
        val tEnd = System.currentTimeMillis()
        logger.lifecycle("package: ${bundle.path} (ad-hoc signed)")
        markApplied(t0)
        // The first four fields keep their meaning (scripts parse them); embed, bundle and the wait for [beside] follow.
        logger.lifecycle("package timing ms=${tEnd - t0} compile=${tCompile - t0} merge=${tMerge - tCompile} embed+bundle=${tBundle - tMerge} " +
            "embed=${tEmbed - tMerge} bundle=${tBundle - tEmbed} baseline-wait=${tEnd - tBundle}")
        packagedStableKeys.writeText(stableKeysState)
        if (libsFromFile && libsEntry.isNotEmpty()) packagedPayloadFile.writeText(libsObj.path) else packagedPayloadFile.delete()
    }

    // ---------------------------------------------------------------- reload

    /** Without a classifier baseline: compile and send; no rebuilt object is "nothing changed", not an error. */
    fun reload(daemons: Daemons?, tools: Tools? = null) {
        val patch = try {
            preparePatch(daemons, tools)
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
    /** The stable-keys state the running app was packaged with (#47); a patch must be compiled the same way. */
    val packagedStableKeys get() = File(work, "stable-keys.txt")

    fun preparePatch(daemons: Daemons?, tools: Tools? = null): File {
        writeGeneratedMain()
        packagedStableKeys.takeIf { it.isFile }?.readText()?.trim()?.let { packaged ->
            if (packaged != stableKeysState) throw GradleException(
                "CMP hot reload: stableKeys is $stableKeysState now but the app was packaged with it $packaged; every compile of one app " +
                    "must agree. Run ./gradlew hotReloadPackage hotReloadRun (or set it back).",
            )
        }
        if (!s.hotSymbols.isFile) throw GradleException("CMP hot reload: no packaged app in ${s.appDir}; run ./gradlew hotReloadPackage first")
        // #7: the sources are byte for byte what the running app was given - nothing to compile or send. (A failed
        // attempt does not update the digest, so the next run still compiles; resource-only changes are judged by
        // the classifier, which then asks for a warm restart.)
        if (appliedSources.isFile && appliedSources.readText() == sourcesDigest()) throw NothingChanged(s.modules.size)
        // #98 (after "nothing changed": with nothing to send there is nothing to refuse): a patch is compiled like the app it goes into; another plugin, toolchain or compiler plugin needs a new package
        BuildIdentity.changes(s)?.let {
            throw GradleException("CMP hot reload: the app was packaged with another build identity ($it); a patch must be compiled " +
                "the same way, nothing was sent. Run ./gradlew hotReloadPackage hotReloadRun.")
        }
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
                val m = moduleOfArchive(a, s.modules) ?: moduleOf(s.modules, modulePackages) { definedSymbols(o) }
                    ?: throw GradleException("CMP hot reload: cannot tell which module ${o.path} belongs to (no Kotlin symbol of a known package)")
                objects.getOrPut(m) { mutableListOf() } += o
            }
        }
        if (objects.isEmpty()) throw NothingChanged(s.modules.size)

        val ordered = s.modules.filter { it in objects }
        logger.lifecycle("changed objects: " + (listOf(s.app) + s.libraries).joinToString(" ") { "${it.label}=${objects[it]?.size ?: 0}" })
        val all = ordered.flatMap { objects.getValue(it) }
        if (tools != null) patchDuplicates(tools, ordered.flatMap { m -> objects.getValue(m).map { m to it } })
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
        if (tools != null && s.keepCompactUnwind) keepCompactUnwind(merged, tools)
        return merged
    }

    /**
     * Issue #91, the patch side of merge-payloads' rule for names that several libraries define: a patch object that
     * uses a name the packaged app keeps only as local copies needs a warm restart ([NeedsWarm]); a definition of such a
     * name in the patch (a downstream module's file rebuilt) becomes local too; names that objects of two modules of this
     * patch define get the same three tiers as at packaging. The objects are the patch's own extracted copies.
     */
    fun patchDuplicates(tools: Tools, objects: List<Pair<HrModule, File>>) {
        val record = File(s.appDir, "merged-bootstrap.dups.json")
        val cmd = listOf("python3", "-I", tools.mergePayloads.path, "--patch-dups", record.path, s.hotSymbols.path) +
            objects.map { (m, o) -> "${m.label}=${o.path}" }
        val r = Proc.run(cmd, s.llvmHome?.let { mapOf("KONAN_LLVM_HOME" to it) } ?: emptyMap(), echo = false)
        r.output.lines().filter { it.startsWith("patch: ") || it.startsWith("duplicate definitions") || it.startsWith("  tier") }
            .forEach { logger.lifecycle(it) }
        when (r.exit) {
            0 -> {}
            3 -> throw NeedsWarm(r.output.lines().filter { it.startsWith("WARM ") })
            else -> throw GradleException("CMP hot reload: merge-payloads.py --patch-dups failed (exit ${r.exit}):\n${r.output.trim().takeLast(3000)}")
        }
    }

    /**
     * INTERFACE §3.3 #1 for the patch, as for the packaged object: the compact-unwind records move to
     * `__KALDO,__kaldo_cu`, where the app's loader turns them into FDEs and registers them. Left in
     * `__LD,__compact_unwind` (where `ld -r` and the compiler put them), no frame of the patch had unwind info: an
     * exception thrown in patched code and caught in patched code ended the app (#80). The one call path is the bundled
     * `merge-payloads.py --rename-compact-unwind` (idempotent; an object without the section stays as it is).
     */
    fun keepCompactUnwind(obj: File, tools: Tools) {
        val out = Proc.check(listOf("python3", "-I", tools.mergePayloads.path, "--rename-compact-unwind", obj.path), echo = false).trim()
        logger.lifecycle("patch unwind info: ${out.lines().lastOrNull().orEmpty()}")
    }

    private fun absoluteRpaths(binary: File): List<String> =
        rpaths(Proc.check(listOf("otool", "-l", binary.path), echo = false)).filter { it.startsWith("/") }

    /**
     * Checks the packager duties of INTERFACE §3.3 on the finished bundle; any miss fails the build:
     * #1 compact unwind kept (merged object has __KALDO,__kaldo_cu), #3 a non-empty hot-symbol list,
     * #5 libKaldo inside Frameworks with the manifest's sha256 and no absolute rpath, #6 a valid signature.
     */
    fun verifyBundle(bundle: File, info: AppInfo, merged: File, payloadFile: File? = null) {
        val problems = mutableListOf<String>()
        // #56: the library object the app loads from its file at start must exist.
        if (payloadFile != null && !payloadFile.isFile) problems += "#56 library payload file ${payloadFile.path} missing"
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
        /**
         * #129: a file's metadata for the klib stamps: path, size, mtime and ctime (nanoseconds) and inode; "missing" when
         * it does not exist. No contents are read.
         */
        fun fileStamp(f: File): String {
            if (!f.exists()) return "${f.path} missing"
            val a = java.nio.file.Files.readAttributes(f.toPath(), "unix:size,lastModifiedTime,ctime,ino", java.nio.file.LinkOption.NOFOLLOW_LINKS)
            fun t(k: String) = (a[k] as java.nio.file.attribute.FileTime).to(java.util.concurrent.TimeUnit.NANOSECONDS)
            return "${f.path} ${a["size"]} ${t("lastModifiedTime")} ${t("ctime")} ${a["ino"]}"
        }

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
        /** #124: output of `compat-classify.py abi-fingerprint`. */
        val ABI_FINGERPRINT = Regex("[0-9a-f]{64}")

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
                val names = keys.map { if (kind == "source" || kind == "external") File(it).name else it.substringBefore(' ') }.distinct()
                return "$label: ${names.take(3).joinToString()}" + (if (names.size > 3) " and ${names.size - 3} more" else "")
            }
            return listOfNotNull(
                describe("source", "sources changed"),
                describe("module", "module klibs changed"),
                describe("external", "dependency klibs changed"),
                if (changed.any { it.startsWith("args ") || it.startsWith("toolchain ") }) "compiler arguments or toolchain changed" else null,
            ).joinToString("; ").ifEmpty { "inputs changed" }
        }

        /** Whether this Kotlin/Native compiler jar knows `-Xbinary=splitPayloadList` (toolchain .9+, #32). */
        fun compilerKnowsSplitPayloadList(jar: File): Boolean = compilerKnowsBinaryOption(jar, "splitPayloadList")

        /** Whether this Kotlin/Native compiler jar knows the binary option [name] (e.g. splitPayloadReserve, toolchain .10+). */
        fun compilerKnowsBinaryOption(jar: File, name: String): Boolean = try {
            java.util.zip.ZipFile(jar).use { z ->
                val e = z.getEntry("org/jetbrains/kotlin/config/nativeBinaryOptions/BinaryOptions.class") ?: return false
                String(z.getInputStream(e).readBytes(), Charsets.ISO_8859_1).contains(name)
            }
        } catch (e: java.io.IOException) {
            false
        }

        /** Whether this libKaldo loads a payload from its file (manifest kind 2, toolchain .10+), as hotreload.sh checks. */
        fun kaldoLoadsPayloadFiles(dylib: File): Boolean = try {
            String(dylib.readBytes(), Charsets.ISO_8859_1).contains("bootstrap payload file")
        } catch (e: java.io.IOException) {
            false
        }

        const val STABLE_KEYS = "HotReloadStableKeys"
        const val STABLE_KEYS_OPTION = "plugin:androidx.compose.compiler.plugins.kotlin:featureFlag=$STABLE_KEYS"

        /** Whether this Compose compiler plugin jar knows [flag] (a FeatureFlag enum entry; toolchain .9+), as hotreload.sh checks. */
        private val LINKAGE = Regex("""NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|AbstractMethodError|IncompatibleClassChangeError|ClassNotFoundException|LinkageError|is not compatible with|incompatible with""")

        /** Classes a compiler plugin jar registers (META-INF/services of the Kotlin compiler), for naming it in errors. */
        fun registeredClasses(jar: File): List<String> = try {
            java.util.zip.ZipFile(jar).use { z ->
                z.entries().toList().filter { it.name.startsWith("META-INF/services/org.jetbrains.kotlin.") }
                    .flatMap { e -> z.getInputStream(e).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") } }
            }
        } catch (_: Exception) {
            emptyList()
        }

        /**
         * #76: when a compile with project compiler plugins [jars] fails like a binary incompatibility (a plugin built for
         * another Kotlin version), the message that names the plugin; null when it does not look like one.
         */
        fun pluginIncompatibility(output: String, jars: List<File>, compiler: String, javaHome: String? = null): String? {
            if (jars.isEmpty()) return null
            classVersionMismatch(output, jars, javaHome)?.let { return it }
            if (!LINKAGE.containsMatchIn(output)) return null
            val named = jars.filter { j ->
                output.contains(j.name) || registeredClasses(j).any { c -> output.contains(c.substringBeforeLast('.')) }
            }.ifEmpty { jars }
            return "CMP hot reload: the project's compiler plugin ${named.joinToString { it.name }} does not work with the toolchain's " +
                "Kotlin/Native compiler $compiler (the hot reload compiles pass the plugins your build applies). Use a version of " +
                "this plugin built for Kotlin $compiler, or remove the plugin from the module."
        }

        /** `UnsupportedClassVersionError: <class> has been compiled by … (class file version N), … up to M`. */
        private val CLASS_VERSION = Regex(
            """([\w/$.]+) has been compiled by a more recent version of the Java Runtime \(class file version (\d+)(?:\.\d+)?\), """ +
                """this version of the Java Runtime only recognizes class file versions up to (\d+)""",
        )

        /** Java feature release of a class file major version (52 = Java 8, 61 = Java 17, 65 = Java 21). */
        fun javaRelease(classFileVersion: Int): Int = classFileVersion - 44

        /**
         * Issue #90: a project compiler plugin compiled for a newer Java than the JDK that runs the hot reload compiler
         * (`UnsupportedClassVersionError`): the message names the plugin jar (the one holding the failing class), the Java
         * it needs, the JDK in use, and how to switch. Null when the output shows no such error.
         */
        fun classVersionMismatch(output: String, jars: List<File>, javaHome: String? = null): String? {
            val m = CLASS_VERSION.find(output) ?: return null
            val entry = m.groupValues[1].replace('.', '/') + ".class"
            val need = javaRelease(m.groupValues[2].toInt())
            val have = javaRelease(m.groupValues[3].toInt())
            val named = jars.filter { j ->
                try {
                    java.util.zip.ZipFile(j).use { it.getEntry(entry) != null }
                } catch (_: Exception) {
                    false
                }
            }.ifEmpty { jars.filter { output.contains(it.name) } }.ifEmpty { jars }
            val jdk = javaHome?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            return "CMP hot reload: the project's compiler plugin ${named.joinToString { it.name }} needs Java $need " +
                "(class file version ${m.groupValues[2]}), but the hot reload compiler runs on Java $have$jdk. Run Gradle with JDK " +
                "$need or newer (JAVA_HOME), or point cmpHotReload { daemonJavaHome } at one."
        }

        fun composePluginKnows(jar: File, flag: String): Boolean = try {
            java.util.zip.ZipFile(jar).use { z ->
                val e = z.getEntry("androidx/compose/compiler/plugins/kotlin/FeatureFlag.class") ?: return false
                String(z.getInputStream(e).readBytes(), Charsets.ISO_8859_1).contains(flag)
            }
        } catch (e: java.io.IOException) {
            false
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

        /**
         * #116: the module whose code a per-file cache archive holds, from its path: a library's klib is
         * `klib/<label>/<label>.klib` ([com.solufee.cmp.hotreload.HrSettings.klibOf]) and the compiler keeps its per-file caches
         * under `<label>-per-file-cache/`; the program's (the app's) directory has a hash name and is left to [classify].
         */
        fun moduleOfArchive(archive: String, modules: List<HrModule>): HrModule? {
            val unit = archive.split('/').lastOrNull { it.endsWith(PER_FILE_CACHE) }?.removeSuffix(PER_FILE_CACHE) ?: return null
            return modules.firstOrNull { it.kind != ModuleKind.APP && it.label == unit }
        }

        private const val PER_FILE_CACHE = "-per-file-cache"

        fun classify(symbols: Set<String>, packages: Map<HrModule, Set<String>>): HrModule? {
            val votes = mutableMapOf<HrModule, Int>()
            for (sym in symbols) {
                // Functions, classes and globals carry the package after their prefix; file initializers
                // (`__Konan_init_<hash>:<package>...`) after the colon. #116: private and local declarations (e.g. Compose
                // lambdas) are qualified with their source file (`_kfun:/path/File.kt:<package>...`), and a file's archive
                // initializer names the archive (`__Konan_init_<klib>:lib<package>.<hash>.a`): the package follows.
                val rest = (KOTLIN_SYMBOL_PREFIXES.firstOrNull { sym.startsWith(it) }?.let { sym.removePrefix(it) }
                    ?: sym.takeIf { it.startsWith("__Konan_init_") && ':' in it }?.substringAfter(':')
                    ?: continue).let { r ->
                        when {
                            r.startsWith("/") && ".kt:" in r -> r.substringAfter(".kt:")
                            sym.startsWith("__Konan_init_") && r.startsWith("lib") && r.endsWith(".a") -> r.removePrefix("lib")
                            else -> r
                        }
                    }
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
