package com.solufee.cmp.hotreload

import java.io.File

/**
 * #133: the project's own compiler settings for hot reload compiles: what the normal build's
 * compileKotlinIosSimulatorArm64 gets from `compilerOptions` (freeCompilerArgs, optIn, languageVersion, apiVersion,
 * progressiveMode, allWarningsAsErrors, suppressWarnings) and `languageSettings` (enabled language features), plus
 * `cmpHotReload { extraCompilerArgs }` (global, then per module). Arguments the plugin owns are dropped (rule table in
 * docs/INTERFACE.md §11); the rest is deduplicated by option, so an option with a value counts as one pair whether it is
 * written `-opt-in X` or `-opt-in=X`.
 */
object CompilerArgs {
    /** One option: [key] and, for options that take a value, [value]. */
    data class Opt(val key: String, val value: String?) {
        /** Options with a value: `-opt-in=X` and `-Xflag=value` as one token, the other [PAIRED] ones as two (KGP's form). */
        fun render(): List<String> = when {
            value == null -> listOf(key)
            key in EQUALS_FORM || key !in PAIRED -> listOf("$key=$value")
            else -> listOf(key, value)
        }
    }

    /** Options followed by a value, written `-key value` (KGP's form) or `-key=value`. */
    val PAIRED = setOf(
        "-opt-in", "-language-version", "-api-version", "-P", "-o", "-output", "-p", "-produce", "-target", "-module-name",
        "-l", "-library", "-linker-option", "-linker-options", "-friend-modules", "-native-library", "-include-binary",
        "-manifest", "-entry", "-e", "-kotlin-home",
    )

    /** Rendered as `-key=value` (one token) so the plugin's own `-opt-in=…` and the project's compare equal. */
    private val EQUALS_FORM = setOf("-opt-in")

    /** Kept once: the last one wins (module's extraCompilerArgs, then global, then the project's build). */
    val SINGLE = setOf("-language-version", "-api-version")

    /** Dropped: the plugin owns them (see docs/INTERFACE.md §11). */
    val DROP = setOf(
        "-Xplugin", "-P", "-o", "-output", "-p", "-produce", "-target", "-g", "-module-name", "-Xshort-module-name",
        "-l", "-library", "-linker-option", "-linker-options", "-friend-modules", "-native-library", "-include-binary",
        "-nopack", "-nostdlib", "-nodefaultlibs", "-manifest", "-entry", "-e", "-kotlin-home",
        "-Xmulti-platform", "-Xfragments", "-Xfragment-refines", "-Xfragment-sources", "-Xrefines-paths",
        "-Xcompilation-scheme", "-Xenable-incremental-compilation", "-Xpartial-linkage", "-Xpartial-linkage-loglevel",
    )

    /** Dropped by prefix (caches, dumps, incremental state). */
    val DROP_PREFIX = listOf("-Xic-", "-Xauto-cache-", "-Xdump-")

    /** Dropped with a warning: they change the binary the package builds, not the language (evaluated case by case). */
    val DROP_WARN = setOf("-Xbinary")

    /** Splits [args] into options; both `-key value` and `-key=value` give one [Opt] for [PAIRED] keys. */
    fun parse(args: List<String>): List<Opt> {
        val out = mutableListOf<Opt>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            val eq = a.indexOf('=')
            val key = if (a.startsWith("-") && eq > 0) a.substring(0, eq) else a
            when {
                key in PAIRED && eq > 0 -> out += Opt(key, a.substring(eq + 1))
                key in PAIRED && i + 1 < args.size -> { out += Opt(key, args[i + 1]); i++ }
                a.startsWith("-") && eq > 0 -> out += Opt(key, a.substring(eq + 1))
                else -> out += Opt(a, null)
            }
            i++
        }
        return out
    }

    /** The project's compiler settings as KGP passes them to the compiler. */
    fun fromProject(
        freeCompilerArgs: List<String>, optIn: List<String>, languageVersion: String?, apiVersion: String?,
        progressive: Boolean, allWarningsAsErrors: Boolean, suppressWarnings: Boolean, languageFeatures: List<String>,
    ): List<String> =
        (if (languageVersion != null) listOf("-language-version", languageVersion) else emptyList()) +
            (if (apiVersion != null) listOf("-api-version", apiVersion) else emptyList()) +
            (if (progressive) listOf("-progressive") else emptyList()) +
            (if (allWarningsAsErrors) listOf("-Werror") else emptyList()) +
            (if (suppressWarnings) listOf("-nowarn") else emptyList()) +
            optIn.flatMap { listOf("-opt-in", it) } +
            languageFeatures.map { "-XXLanguage:+$it" } +
            freeCompilerArgs

    /** Result of [merge]: the arguments for hot reload compiles, and what was left out (with why). */
    data class Merged(val args: List<String>, val dropped: List<String>, val warnings: List<String>)

    /**
     * The project's arguments, then the global, then the module's extraCompilerArgs: plugin-owned options dropped,
     * `-opt-in` merged by value (comma lists split), [SINGLE] options kept once (the last wins), everything else kept
     * once in the order first seen.
     */
    fun merge(project: List<String>, global: List<String>, module: List<String>): Merged {
        val dropped = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val kept = mutableListOf<Opt>()
        for (o in parse(project) + parse(global) + parse(module)) {
            when {
                o.key in DROP || DROP_PREFIX.any { o.key.startsWith(it) } -> dropped += o.render().joinToString(" ")
                o.key in DROP_WARN -> {
                    dropped += o.render().joinToString(" ")
                    warnings += "${o.render().joinToString(" ")} is not passed to hot reload compiles (binary options are the plugin's)"
                }
                o.key == "-opt-in" -> o.value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { v ->
                    if (kept.none { it.key == "-opt-in" && it.value == v }) kept += Opt("-opt-in", v)
                }
                o.key in SINGLE -> { kept.removeAll { it.key == o.key }; kept += o }
                kept.none { it == o } -> kept += o
            }
        }
        return Merged(kept.flatMap { it.render() }, dropped, warnings)
    }

    /**
     * #137: the package and patch compiles use the split compilation scheme (`-Xcompilation-scheme=split`, the
     * plugin's own), for which Kotlin/Native always prints "split compilation is an experimental feature …"; with
     * `-Werror` (allWarningsAsErrors) that warning alone fails the compile. So those two compiles do not get `-Werror`;
     * every klib compile (library modules, and the app module's klib when packaging) still does. Logged once at info.
     */
    fun withoutWerrorForSplit(args: List<String>, logger: org.gradle.api.logging.Logger?): List<String> {
        if ("-Werror" !in args) return args
        if (werrorNoted.compareAndSet(false, true)) {
            logger?.info("CMP hot reload: -Werror (allWarningsAsErrors) is not passed to the package and patch compiles: their split " +
                "compilation scheme always prints a compiler warning; the klib compiles keep it")
        }
        return args.filter { it != "-Werror" }
    }

    private val werrorNoted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** [args] without the options [base] (the plugin's own arguments of that compile) already has, pairs included. */
    fun without(args: List<String>, base: List<String>): List<String> {
        val have = parse(base).toSet()
        return parse(args).filter { it !in have }.flatMap { it.render() }
    }

    /**
     * Language and API versions the toolchain's compiler knows (`LanguageVersion` constants of its compiler jar, e.g.
     * KOTLIN_2_1 -> "2.1"); null when they cannot be read.
     */
    fun knownLanguageVersions(compilerJar: File): Set<String>? = try {
        java.util.zip.ZipFile(compilerJar).use { z ->
            val e = z.getEntry("org/jetbrains/kotlin/config/LanguageVersion.class") ?: return null
            val text = String(z.getInputStream(e).readBytes(), Charsets.ISO_8859_1)
            Regex("""KOTLIN_(\d+)_(\d+)""").findAll(text).map { "${it.groupValues[1]}.${it.groupValues[2]}" }.toSet().takeIf { it.isNotEmpty() }
        }
    } catch (e: java.io.IOException) {
        null
    }

    /** A message for each `-language-version` / `-api-version` in [args] that [known] does not contain. */
    fun unsupportedVersions(args: List<String>, known: Set<String>?, compilerVersion: String): List<String> {
        if (known == null) return emptyList()
        return parse(args).filter { it.key in SINGLE && it.value != null && it.value !in known }.map {
            "${it.key} ${it.value} is not a version the toolchain's compiler (Kotlin $compilerVersion) knows " +
                "(it knows ${known.sortedWith(compareBy({ v -> v.substringBefore('.').toInt() }, { v -> v.substringAfter('.').toInt() })).joinToString()}); " +
                "set a supported one for hot reload with cmpHotReload { extraCompilerArgs(\"<module path>\", \"${it.key}\", \"<version>\") } " +
                "(a module's extraCompilerArgs win over the global ones and the build's)"
        }
    }
}
