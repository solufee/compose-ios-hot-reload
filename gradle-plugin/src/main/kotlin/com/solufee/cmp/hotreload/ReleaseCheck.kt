package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File

/**
 * Release zero-change check. Builds the app the way a release build does — `-opt`, no split scheme, no
 * incremental or auto caches, no hot-reload switches, scrubbed environment, fixed module name and TMPDIR —
 * and fingerprints the binary:
 * every section's size, the defined-symbol table, and the bytes of __TEXT,__text. (Whole-file hashes are not
 * used: two builds of the same input differ in UUID, string table order and LINKEDIT.)
 *
 * Use: record a baseline before any hot-reload activity, run a hot-reload session (package, run, reloads),
 * then verify again; the fingerprints must be identical. The exact commands are written next to the binary,
 * so the same build can be reproduced outside Gradle and compared too.
 */
class ReleaseCheck(private val s: HrSettings, private val logger: Logger) {
    private val dir = File(s.workDir, "release")

    data class Fingerprint(val sections: Map<String, Long>, val symbolCount: Int, val symbolsSha: String, val textSha: String) {
        fun render(): String = buildString {
            appendLine("text.sha256=$textSha")
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
                )
            }
        }
    }

    /** Release build commands: one per library (dependency order), then the app. */
    fun commands(out: File): List<List<String>> {
        val ext = Pipeline(s, logger).externalKlibs().flatMap { listOf("-l", it) }
        val klibDir = File(out, "klib")
        fun klib(m: HrModule) = File(klibDir, "${m.label}.klib")
        val libs = s.libraries.map { m ->
            listOf(s.kotlinc, "-target", s.target, "-p", "library", "-opt",
                "-Xplugin=${s.composePlugin}", "-opt-in=kotlinx.cinterop.ExperimentalForeignApi") +
                ext + s.libraries.filter { it.path in m.deps }.flatMap { listOf("-l", klib(it).path) } +
                listOf(m.srcDir, "-o", klib(m).path.removeSuffix(".klib"))
        }
        // A program compiled without -module-name gets a generated module name that changes on every build;
        // it ends up in the cinterop bridge names (`<module><file>_N_kncfunN`), whose sort order then moves
        // data slots and changes __text. A fixed name makes the release build deterministic.
        val exe = Pipeline(s, logger).appInfo().executable
        val app = listOf(s.kotlinc, "-target", s.target, "-p", "program", "-opt", "-module-name", exe,
            "-Xplugin=${s.composePlugin}", "-opt-in=kotlinx.cinterop.ExperimentalForeignApi") +
            s.linkerOptions.map { "-linker-option=$it" } +
            listOf("-linker-option=-force_load", "-linker-option=${s.objcUtilsLib}") +
            ext + s.libraries.flatMap { listOf("-l", klib(it).path) } +
            listOf("-o", File(out, "$exe-release.kexe").path, s.app.srcDir)
        return libs + listOf(app)
    }

    fun build(out: File): File {
        out.deleteRecursively()
        File(out, "klib").mkdirs()
        val cmds = commands(out)
        // The compiler's temporary-file location changes the order of some data slots (and so __text);
        // a fixed private TMPDIR makes the build reproducible in and outside Gradle.
        val tmp = File(dir, "tmp").apply { deleteRecursively(); mkdirs() }
        File(out, "commands.sh").writeText(
            "#!/bin/bash\n# Release build, reproducible outside Gradle (run with a clean environment)\nset -e\n" +
                "rm -rf '${tmp.path}' && mkdir -p '${tmp.path}'\nexport TMPDIR='${tmp.path}'\n" +
                cmds.joinToString("\n") { c -> c.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" } } + "\n",
        )
        // Only JAVA_HOME and PATH come from outside; every hot-reload switch is removed.
        val env = mapOf("JAVA_HOME" to s.javaHome, "TMPDIR" to tmp.path)
        val labels = s.libraries.map { it.label } + s.app.label
        for ((i, c) in cmds.withIndex()) {
            logger.lifecycle("release: compiling ${labels[i]}")
            Proc.check(c, env, logger = logger, echo = false)
        }
        return File(cmds.last()[cmds.last().indexOf("-o") + 1])
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
        )
    }

    /**
     * The pinned toolchain's release output is not bit-reproducible: two data slots in __DATA_CONST,__const
     * swap order between builds at random, flipping 78 bytes of __text (seen without the plugin; see
     * example-gradle/accept-plugin-p1-release-control.txt). Baseline mode therefore builds repeatedly until it
     * has seen [wantedVariants] distinct __text hashes or [maxRuns] builds; sizes and symbols must be identical
     * across all of them. Verify mode builds once and requires identical sizes and symbols and a known __text hash.
     */
    fun run(recordBaseline: Boolean, maxRuns: Int = 6, wantedVariants: Int = 2) {
        val baselineFile = File(dir, "baseline.fingerprint")
        val allowedFile = File(dir, "baseline.text-hashes")
        if (recordBaseline) {
            val out = File(dir, "baseline")
            var first: Fingerprint? = null
            val hashes = linkedSetOf<String>()
            for (run in 1..maxRuns) {
                val fp = fingerprint(build(out))
                File(out, "fingerprint.txt").writeText(fp.render())
                if (first == null) first = fp
                else if (first.symbolsSha != fp.symbolsSha || first.sections != fp.sections) {
                    throw GradleException("CMP hot reload: two baseline builds of unchanged sources differ in sizes or symbols; " +
                        "the inputs changed during the baseline (see ${out.path})")
                }
                hashes += fp.textSha
                logger.lifecycle("release baseline build $run/$maxRuns: __text ${fp.textSha.take(12)} (${hashes.size} variant(s) so far)")
                if (hashes.size >= wantedVariants) break
            }
            baselineFile.writeText(first!!.render())
            allowedFile.writeText(hashes.joinToString("\n", postfix = "\n"))
            logger.lifecycle("release baseline recorded: ${baselineFile.path} (${first.symbolCount} symbols, ${first.sections.size} sections; " +
                "allowed __text ${hashes.joinToString { it.take(12) }})")
            return
        }
        val out = File(dir, "current")
        val fp = fingerprint(build(out))
        File(out, "fingerprint.txt").writeText(fp.render())
        if (!baselineFile.isFile) throw GradleException("CMP hot reload: no release baseline; run hotReloadVerifyRelease -Pcmp.hotreload.releaseBaseline=true first")
        val base = Fingerprint.parse(baselineFile.readText())
        val diffs = buildList {
            val allowed = if (allowedFile.isFile) allowedFile.readLines().filter { it.isNotBlank() } else listOf(base.textSha)
            if (fp.textSha !in allowed) {
                add("__TEXT,__text bytes ${fp.textSha.take(12)} not among the baseline's ${allowed.joinToString { it.take(12) }}")
            }
            if (base.symbolsSha != fp.symbolsSha) add("symbol table differs (${base.symbolCount} -> ${fp.symbolCount} symbols)")
            (base.sections.keys + fp.sections.keys).sorted().forEach { k ->
                if (base.sections[k] != fp.sections[k]) add("size of $k differs (${base.sections[k]} -> ${fp.sections[k]})")
            }
        }
        if (diffs.isNotEmpty()) {
            throw GradleException("CMP hot reload: release build changed:\n" + diffs.joinToString("\n") { "  - $it" } +
                "\n  baseline ${baselineFile.path}, current ${File(out, "fingerprint.txt").path}")
        }
        logger.lifecycle("release unchanged: ${fp.symbolCount} symbols and ${fp.sections.size} section sizes identical to the baseline, " +
            "__text ${fp.textSha.take(12)} among the baseline's hashes")
    }
}
