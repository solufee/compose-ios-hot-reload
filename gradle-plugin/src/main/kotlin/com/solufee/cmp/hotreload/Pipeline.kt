package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File

/**
 * The hot-reload pipeline, implemented in the plugin (P1). It calls only the tools and conventions of
 * docs/INTERFACE.md: kotlinc-native / HrCompileDaemon, merge-payloads.py, embed-merged.py, the reload protocol.
 */
class Pipeline(private val s: HrSettings, private val logger: Logger) {
    private val work = File(s.workDir)

    // ---------------------------------------------------------------- arguments

    /**
     * Klibs from the toolchain klib list, minus the dist's own stdlib/platform klibs (<dist>/klib/, which the
     * compiler adds implicitly). Everything else is linked wherever it lives, including public klibs that the
     * toolchain download put next to the dist; nothing is assumed to be in ~/.gradle.
     */
    fun externalKlibs(): List<String> = externalKlibs(File(s.klibList).readLines(), File(s.dist))


    /** Longest common directory of the external klibs: caches are auto-built for everything below it. */
    fun autoCacheFrom(klibs: List<String>): String {
        if (klibs.isEmpty()) return File(System.getProperty("user.home"), ".gradle/caches").path
        var prefix = File(klibs.first()).parentFile
        for (k in klibs.drop(1)) {
            while (prefix != null && !File(k).canonicalPath.startsWith(prefix.canonicalPath + "/")) prefix = prefix.parentFile
        }
        return prefix?.path ?: "/"
    }

    private fun klibLinks(m: HrModule?): List<String> {
        val libs = if (m == null) s.libraries else s.libraries.filter { it.path in m.deps }
        return externalKlibs().flatMap { listOf("-l", it) } + libs.flatMap { listOf("-l", s.klibOf(it).path) }
    }

    fun libraryArgs(m: HrModule, out: File = s.klibOf(m)): List<String> = listOf(
        "-target", s.target, "-p", "library", "-g",
        "-Xplugin=${s.composePlugin}", "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
    ) + klibLinks(m) + listOf(m.srcDir, "-o", out.path.removeSuffix(".klib"))

    /** Identical for package and reload, so the incremental caches are shared (only -o and the dump differ). */
    fun programArgs(output: File, dumpCaches: File?): List<String> {
        val ext = externalKlibs()
        return listOf(
            "-target", s.target, "-p", "program", "-g",
            "-Xcompilation-scheme=split",
            "-Xenable-incremental-compilation", "-Xic-cache-dir=${File(work, "ic").path}",
            "-Xbinary=perFileCacheForStdlib=false",
            "-Xauto-cache-from=${autoCacheFrom(ext)}",
            "-Xauto-cache-dir=${File(work, "auto-cache").path}",
            "-Xplugin=${s.composePlugin}",
            "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
        ) + s.linkerOptions.map { "-linker-option=$it" } +
            listOf("-linker-option=-force_load", "-linker-option=${s.objcUtilsLib}") +
            klibLinks(null) +
            (dumpCaches?.let { listOf("-Xdump-built-caches-to=${it.path}") } ?: emptyList()) +
            listOf("-o", output.path, s.app.srcDir)
    }

    // ---------------------------------------------------------------- compile

    private fun compile(args: List<String>, daemonSocket: String?, kind: String, cacheOnly: Boolean, daemons: Daemons?) {
        if (daemonSocket != null && daemons != null) {
            val offset = daemons.logSize(kind)
            when (val r = CompileDaemonClient.request(daemonSocket, args)) {
                is CompileResult.Ok -> {
                    logger.lifecycle("compile: daemon $kind OK ${r.ms} ms")
                    return
                }
                is CompileResult.Err -> throw GradleException(
                    "CMP hot reload: compilation failed (${r.code}, ${r.ms} ms):\n" + compilerMessages(daemons.logSince(kind, offset)),
                )
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
    }

    /** Daemon log lines that look like compiler diagnostics, without the daemon's own bookkeeping. */
    private fun compilerMessages(log: String): String =
        log.lines().filterNot { it.startsWith("REQ ") || it.isBlank() }.joinToString("\n").takeLast(6000)

    fun compileLibrary(m: HrModule, daemons: Daemons?) {
        val out = s.klibOf(m)
        out.parentFile.mkdirs()
        out.deleteRecursively()
        compile(libraryArgs(m), if (daemons != null) s.klibSocket else null, "klib", cacheOnly = false, daemons = daemons)
        if (!out.exists()) throw GradleException("CMP hot reload: compiler reported success but ${out.path} is missing")
        logger.lifecycle("klib ${m.label}: ${out.path}")
    }

    /**
     * The app module compiled as a full (IR-bearing) library klib. Only the compatibility classifier uses it:
     * metadata alone does not show inline/suspend body changes (INTERFACE §10.2).
     */
    fun compileAppKlib(daemons: Daemons?): File {
        val out = File(s.compatDir, "app.klib")
        out.parentFile.mkdirs()
        out.deleteRecursively()
        compile(libraryArgs(s.app, out), if (daemons != null) s.klibSocket else null, "klib", cacheOnly = false, daemons = daemons)
        if (!out.exists()) throw GradleException("CMP hot reload: compiler reported success but ${out.path} is missing")
        return out
    }

    // ---------------------------------------------------------------- packages / module mapping

    /** Packages declared in a module's sources ("" = root package). */
    fun packagesOf(m: HrModule): Set<String> = sourcePackages(File(m.srcDir))

    /** Hot packages for merge-payloads: every package of every library, plus the app's named packages. */
    fun hotPackages(): List<String> =
        (s.libraries.flatMap { packagesOf(it) } + packagesOf(s.app)).filter { it.isNotEmpty() }.distinct().sorted()

    // ---------------------------------------------------------------- package

    data class AppInfo(val executable: String, val bundleId: String)

    fun appInfo(): AppInfo {
        fun read(key: String) = Proc.check(listOf("/usr/libexec/PlistBuddy", "-c", "Print :$key", s.infoPlist), echo = false).trim()
        return AppInfo(read("CFBundleExecutable"), read("CFBundleIdentifier"))
    }

    fun appBundle(info: AppInfo = appInfo()): File = File(work, "${info.executable}.app")

    fun packageApp(tools: Tools) {
        val info = appInfo()
        val app = s.appDir.apply { mkdirs() }
        listOf("ic", "auto-cache").forEach { File(work, it).mkdirs() }
        // The program's module name comes from its output name and prefixes the exported ObjC classes
        // (e.g. ComposeHRBase*). Keep the reference driver's names: a bare "base" collides at bootstrap.
        val base = File(app, "${info.executable}-base.kexe")
        compile(programArgs(base, null), null, "program", cacheOnly = false, daemons = null)

        val merged = File(app, "merged-bootstrap.o")
        val packages = hotPackages()
        val env = mergeEnvironment(packages)
        logger.lifecycle("hot packages: ${packages.joinToString(",").ifEmpty { "<none>" }}; keep compact unwind: ${s.keepCompactUnwind}")
        Proc.check(listOf("python3", "-I", tools.mergePayloads.path, base.path, merged.path), env, logger = logger, echo = false)
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
        logger.lifecycle("package: ${bundle.path} (ad-hoc signed)")
    }

    // ---------------------------------------------------------------- reload

    fun reload(daemons: Daemons?) = sendPatch(preparePatch(daemons))

    fun sendPatch(merged: File) {
        val log = File(s.launchLog)
        val before = if (log.isFile) log.length() else -1L
        val t0 = System.currentTimeMillis()
        ReloadSender.send(s.port, listOf(merged.path))
        logger.lifecycle("sent ${merged.path} port ${s.port}")
        if (before >= 0) confirmLoaded(log, before, t0)
    }

    /**
     * The app's loader (libKaldo) logs `[kaldo] ::` lines while it links a patch. Seeing them in the launch log
     * confirms the running app took the patch; the new code then shows on the next recomposition.
     */
    private fun confirmLoaded(log: File, from: Long, t0: Long) {
        val deadline = t0 + 5000
        while (System.currentTimeMillis() < deadline) {
            val text = log.inputStream().use { input ->
                input.skip(from)
                input.readBytes().toString(Charsets.UTF_8)
            }
            if (text.contains("[kaldo] ::")) {
                logger.lifecycle("app loaded the patch (${System.currentTimeMillis() - t0} ms); it shows on the next recomposition")
                return
            }
            Thread.sleep(100)
        }
        logger.warn("CMP hot reload: no reaction from the app in ${log.path} within 5 s; is it running (./gradlew hotReloadRun) and listening on port ${s.port}?")
    }

    /** Compile (cache-only), collect this run's objects, merge them and check the symbols; returns the object to send. */
    fun preparePatch(daemons: Daemons?): File {
        if (!s.hotSymbols.isFile) throw GradleException("CMP hot reload: no packaged app in ${s.appDir}; run ./gradlew hotReloadPackage first")
        // Archives are filtered by mtime against this start time (second granularity, like the reference driver).
        val start = System.currentTimeMillis() / 1000 * 1000
        val caches = File(work, "caches.txt").apply { delete() }
        val patchExe = File(s.appDir, "${appInfo().executable}-patch.kexe")
        compile(programArgs(patchExe, caches), if (daemons != null) s.compileSocket else null, "compile", cacheOnly = true, daemons = daemons)
        if (!caches.isFile) throw GradleException("CMP hot reload: compiler wrote no ${caches.name}")

        val patchDir = File(work, "patch").apply { deleteRecursively(); mkdirs() }
        val modulePackages = s.modules.associateWith { packagesOf(it) }
        val objects = linkedMapOf<HrModule, MutableList<File>>()
        caches.readLines().map { it.trim() }.filter { it.contains("per-file-cache/") && it.endsWith(".a") }.forEachIndexed { i, a ->
            val archive = File(a)
            if (archive.lastModified() < start) {
                logger.info("skip stale: $a")
                return@forEachIndexed
            }
            val dir = File(patchDir, "a$i-${archive.nameWithoutExtension}").apply { mkdirs() }
            Proc.check(listOf("ar", "x", archive.path), cwd = dir, echo = false)
            dir.listFiles { f -> f.name.endsWith(".o") }!!.sorted().forEach { o ->
                val m = classify(definedSymbols(o), modulePackages)
                    ?: throw GradleException("CMP hot reload: cannot tell which module ${o.path} belongs to (no matching _kfun: symbols)")
                objects.getOrPut(m) { mutableListOf() } += o
            }
        }
        if (objects.isEmpty()) throw GradleException("CMP hot reload: no module object was rebuilt; nothing to send")

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
        fun classify(symbols: Set<String>, packages: Map<HrModule, Set<String>>): HrModule? {
            val votes = mutableMapOf<HrModule, Int>()
            for (sym in symbols) {
                if (!sym.startsWith("_kfun:")) continue
                val rest = sym.removePrefix("_kfun:")
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
