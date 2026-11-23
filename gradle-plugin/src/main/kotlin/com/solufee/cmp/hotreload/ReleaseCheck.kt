package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File

/**
 * Release zero-change check. Builds the app the way a release build does — `-opt`, no split scheme, no
 * incremental or auto caches, no hot-reload switches, scrubbed environment, fixed module name and TMPDIR —
 * and fingerprints the binary:
 * every section's size, the defined-symbol table, and a digest of __TEXT,__text that does not depend on GOT slot
 * order (release-text-digest.py: Apple's ld can give one import two GOT slots in either order, which moves a few
 * dozen `ldr` immediates between identical builds; the digest replaces each GOT load by the symbol it binds).
 * The raw __text hash is recorded, not judged. (Whole-file hashes are not used: two builds of the same input
 * differ in UUID, string table order and LINKEDIT.)
 *
 * Use: record a baseline before any hot-reload activity, run a hot-reload session (package, run, reloads),
 * then verify again; the fingerprints must be identical. The exact commands are written next to the binary,
 * so the same build can be reproduced outside Gradle and compared too.
 */
class ReleaseCheck(private val s: HrSettings, private val logger: Logger) {
    private val dir = File(s.workDir, "release")

    data class Fingerprint(
        val sections: Map<String, Long>, val symbolCount: Int, val symbolsSha: String, val textSha: String, val textDigest: String?,
    ) {
        fun render(): String = buildString {
            appendLine("text.sha256=$textSha")
            textDigest?.let { appendLine("text.digest=$it") }
            appendLine("symbols.count=$symbolCount")
            appendLine("symbols.sha256=$symbolsSha")
            sections.toSortedMap().forEach { (k, v) -> appendLine("section.$k=$v") }
        }

        companion object {
            fun parse(text: String): Fingerprint {
                val m = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
                return Fingerprint(
                    sections = m.filterKeys { it.startsWith("section.") }.mapKeys { it.key.removePrefix("section.") }.mapValues { it.value.toLong() },
                    symbolCount = m.getValue("symbols.count").toInt(),
                    symbolsSha = m.getValue("symbols.sha256"),
                    textSha = m.getValue("text.sha256"),
                    textDigest = m["text.digest"],
                )
            }
        }
    }

    /** Release build commands: one per library (dependency order), then the app. */
    fun commands(out: File): List<List<String>> {
        val pipeline = Pipeline(s, logger)
        pipeline.writeGeneratedMain()
        val ext = pipeline.externalKlibs().flatMap { listOf("-l", it) }
        val klibDir = File(out, "klib")
        fun klib(m: HrModule) = File(klibDir, "${m.label}.klib")
        val libs = s.libraries.map { m ->
            listOf(s.kotlinc, "-target", s.target, "-p", "library", "-opt",
                "-Xplugin=${s.composePlugin}", "-opt-in=kotlinx.cinterop.ExperimentalForeignApi") +
                pipeline.projectPluginArgs(m) + ext + s.libraries.filter { it.path in m.deps }.flatMap { listOf("-l", klib(it).path) } +
                pipeline.sourceArgs(m) + listOf("-o", klib(m).path.removeSuffix(".klib"))
        }
        // A program compiled without -module-name gets a generated module name that changes on every build;
        // it ends up in the cinterop bridge names (`<module><file>_N_kncfunN`), whose sort order then moves
        // data slots and changes __text. A fixed name makes the release build deterministic.
        val exe = pipeline.appInfo().executable
        val app = listOf(s.kotlinc, "-target", s.target, "-p", "program", "-opt", "-module-name", exe,
            "-Xplugin=${s.composePlugin}", "-opt-in=kotlinx.cinterop.ExperimentalForeignApi") +
            pipeline.projectPluginArgs(s.app) + s.linkerOptions.map { "-linker-option=$it" } +
            listOf("-linker-option=-force_load", "-linker-option=${s.objcUtilsLib}") +
            ext + s.libraries.flatMap { listOf("-l", klib(it).path) } +
            listOf("-o", File(out, "$exe-release.kexe").path) + pipeline.sourceArgs(s.app)
        return libs + listOf(app)
    }

    fun build(out: File): File {
        out.deleteRecursively()
        File(out, "klib").mkdirs()
        val cmds = commands(out)
        // The compiler's temporary-file location changes the order of some data slots (and so __text);
        // a fixed private TMPDIR makes the build reproducible in and outside Gradle. #120: on macOS the JVM takes
        // java.io.tmpdir from the system, not from TMPDIR, so the compiler JVM gets it explicitly (JAVA_OPTS, read by
        // kotlinc-native); otherwise the static libraries it unpacks for the link (included<digits>) land in $TMPDIR.
        val tmp = File(dir, "tmp").apply { deleteRecursively(); mkdirs() }
        File(out, "commands.sh").writeText(
            "#!/bin/bash\n# Release build, reproducible outside Gradle (run with a clean environment)\nset -e\n" +
                "rm -rf '${tmp.path}' && mkdir -p '${tmp.path}'\nexport TMPDIR='${tmp.path}'\n" +
                "export JAVA_OPTS='-Djava.io.tmpdir=${tmp.path}'\n" +
                cmds.joinToString("\n") { c -> c.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" } } + "\n",
        )
        // Only JAVA_HOME and PATH come from outside; every hot-reload switch is removed.
        val env = environment(s.javaHome, tmp)
        val labels = s.libraries.map { it.label } + s.app.label
        for ((i, c) in cmds.withIndex()) {
            logger.lifecycle("release: compiling ${labels[i]}")
            Proc.check(c, env, logger = logger, echo = false)
        }
        return File(cmds.last()[cmds.last().indexOf("-o") + 1])
    }

    /** GOT-order-independent digest of __TEXT,__text (bundled release-text-digest.py; needs Xcode's dyld_info). */
    fun textDigest(kexe: File): String {
        val tools = Tools.extract(File(s.workDir), logger)
        val out = Proc.check(listOf("python3", "-I", tools.releaseTextDigest.path, kexe.path), echo = false)
        return out.trim().split(Regex("\\s+")).first().also {
            if (!Regex("[0-9a-f]{64}").matches(it)) throw GradleException("CMP hot reload: release-text-digest.py printed no digest for ${kexe.path}: $out")
        }
    }

    fun fingerprint(kexe: File): Fingerprint {
        val sections = mutableMapOf<String, Long>()
        var segment = ""
        for (line in Proc.check(listOf("size", "-m", kexe.path), echo = false).lines()) {
            val t = line.trim()
            Regex("""^Segment (\S+): (\d+)""").find(t)?.let { segment = it.groupValues[1]; sections["$segment"] = it.groupValues[2].toLong() }
            Regex("""^Section (\S+): (\d+)""").find(t)?.let { sections["$segment,${it.groupValues[1]}"] = it.groupValues[2].toLong() }
        }
        val symbols = Proc.check(listOf("nm", "-U", "-j", kexe.path), echo = false).lines().filter { it.isNotBlank() }.sorted()
        File(kexe.parentFile, kexe.nameWithoutExtension + ".symbols.txt").writeText(symbols.joinToString("\n") + "\n")
        val text = File(kexe.parentFile, kexe.nameWithoutExtension + ".text.bin").apply { delete() }
        Proc.check(listOf("segedit", kexe.path, "-extract", "__TEXT", "__text", text.path), echo = false)
        return Fingerprint(
            sections = sections,
            symbolCount = symbols.size,
            symbolsSha = Tools.sha256(symbols.joinToString("\n").toByteArray()),
            textSha = Tools.sha256(text.readBytes()),
            textDigest = textDigest(kexe),
        )
    }

    /**
     * Baseline mode builds [runs] times (default once) and records the fingerprint; with more than one run the
     * builds must agree (a determinism check). Verify mode builds once and requires identical section sizes,
     * symbol table and __text digest; the raw __text hash is only reported (it may differ by GOT slot order,
     * REPORT sec.28).
     */
    fun run(recordBaseline: Boolean, runs: Int = 1) {
        val baselineFile = File(dir, "baseline.fingerprint")
        val identityFile = File(dir, "baseline.identity")
        if (recordBaseline) {
            val out = File(dir, "baseline")
            var first: Fingerprint? = null
            for (run in 1..runs.coerceAtLeast(1)) {
                val fp = fingerprint(build(out))
                File(out, "fingerprint.txt").writeText(fp.render())
                if (first == null) first = fp
                else diffs(first, fp).takeIf { it.isNotEmpty() }?.let {
                    throw GradleException("CMP hot reload: two baseline builds of unchanged sources differ (the inputs changed during the baseline?):\n" +
                        it.joinToString("\n") { d -> "  - $d" })
                }
                logger.lifecycle("release baseline build $run/$runs: __text digest ${fp.textDigest?.take(12)} (raw __text ${fp.textSha.take(12)})")
            }
            baselineFile.writeText(first!!.render())
            // #98: what the baseline was built with; a later toolchain or plugin change is named instead of reported as a leak
            identityFile.writeText(BuildIdentity.lines(s).joinToString("\n", postfix = "\n"))
            File(dir, "baseline.text-hashes").delete()
            logger.lifecycle("release baseline recorded: ${baselineFile.path} (${first.symbolCount} symbols, ${first.sections.size} sections, " +
                "__text digest ${first.textDigest?.take(12)})")
            return
        }
        val out = File(dir, "current")
        val fp = fingerprint(build(out))
        File(out, "fingerprint.txt").writeText(fp.render())
        if (!baselineFile.isFile) throw GradleException("CMP hot reload: no release baseline; run hotReloadVerifyRelease -Pcmp.hotreload.releaseBaseline=true first")
        val base = Fingerprint.parse(baselineFile.readText())
        if (base.textDigest == null) {
            throw GradleException("CMP hot reload: the release baseline was recorded by an older plugin (no __text digest); " +
                "record it again: hotReloadVerifyRelease -Pcmp.hotreload.releaseBaseline=true")
        }
        val diffs = diffs(base, fp)
        val changed = identityFile.takeIf { it.isFile }?.let { identityChanges(it.readLines(), BuildIdentity.lines(s)) }
        if (diffs.isNotEmpty() && !changed.isNullOrEmpty()) {
            // another compiler builds other code: the comparison says nothing about hot reload
            throw GradleException("CMP hot reload: the release baseline was recorded with another toolchain or plugin (" +
                changed.joinToString(", ") + "), so it cannot be compared with this build:\n" + diffs.joinToString("\n") { "  - $it" } +
                "\n  record it again before any hot reload activity: hotReloadVerifyRelease -Pcmp.hotreload.releaseBaseline=true")
        }
        if (diffs.isNotEmpty()) {
            throw GradleException("CMP hot reload: release build changed:\n" + diffs.joinToString("\n") { "  - $it" } +
                "\n  baseline ${baselineFile.path}, current ${File(out, "fingerprint.txt").path}")
        }
        if (!changed.isNullOrEmpty()) logger.lifecycle("release: the baseline was recorded with other ${changed.joinToString(", ")}; the release build is identical anyway")
        logger.lifecycle("release unchanged: ${fp.symbolCount} symbols, ${fp.sections.size} section sizes and the __text digest " +
            "${fp.textDigest?.take(12)} identical to the baseline (raw __text ${base.textSha.take(12)} -> ${fp.textSha.take(12)}, recorded only)")
    }

    companion object {
        /** The release compiles' environment: the JDK and a private temp directory, for the tools (TMPDIR) and the compiler JVM (#120). */
        fun environment(javaHome: String, tmp: File): Map<String, String> =
            mapOf("JAVA_HOME" to javaHome, "TMPDIR" to tmp.path, "JAVA_OPTS" to "-Djava.io.tmpdir=${tmp.path}")

        /** Identity keys (the first word of each line) whose values differ; keys that only describe hot reload builds are left out. */
        fun identityChanges(baseline: List<String>, current: List<String>): List<String> {
            val hotReloadOnly = setOf("abi", "packaging", "kaldo", "llvm")
            val old = baseline.filter { it.isNotBlank() }.associateBy { it.substringBefore(' ') }
            return current.filter { it.substringBefore(' ') !in hotReloadOnly && old[it.substringBefore(' ')] != it }.map { it.substringBefore(' ') }
        }

        /** What differs between two release fingerprints: sizes, symbols, __text digest (raw __text hash not judged). */
        fun diffs(base: Fingerprint, fp: Fingerprint): List<String> = buildList {
            if (base.textDigest != fp.textDigest) add("__TEXT,__text digest ${base.textDigest?.take(12)} -> ${fp.textDigest?.take(12)}")
            if (base.symbolsSha != fp.symbolsSha) add("symbol table differs (${base.symbolCount} -> ${fp.symbolCount} symbols)")
            (base.sections.keys + fp.sections.keys).sorted().forEach { k ->
                if (base.sections[k] != fp.sections[k]) add("size of $k differs (${base.sections[k]} -> ${fp.sections[k]})")
            }
        }
    }
}
