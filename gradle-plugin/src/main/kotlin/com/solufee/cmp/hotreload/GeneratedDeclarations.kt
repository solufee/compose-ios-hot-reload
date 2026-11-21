package com.solufee.cmp.hotreload

import java.io.File

/**
 * #76: declarations in generated source (every source directory of a hot module inside its build directory, as
 * `project.layout.buildDirectory` resolves it: the output
 * of any code generator, such as an annotation processor, that the build registers on its Kotlin source sets). What a framework builds from them
 * once at start (registries, dependency injection containers, ...) cannot be patched in a running app, so when generated
 * code gains or loses a top-level declaration or class the change is a warm restart: never HOT, never "nothing changed".
 *
 * The snapshot is the set of top-level declarations (`<package> <kind> <name>`, from lines that start at column 0) of
 * every Kotlin file in those directories; it is recorded whenever the app gets new code.
 */
object GeneratedDeclarations {
    private val PACKAGE = Regex("""^package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
    private val MODIFIER = "(?:public|internal|private|protected|data|sealed|abstract|open|final|enum|annotation|value|inline|" +
        "expect|actual|external|const|lateinit|suspend|operator|infix|tailrec|override)"
    private val DECL = Regex("""^(?:$MODIFIER\s+)*(fun\s+interface|class|object|interface|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?([^\s(:<{=]+)""")

    /** Source directories under the hot modules' build directories (by path; the project may itself live under a `build` folder). */
    fun dirs(s: HrSettings): List<File> = s.modules.flatMap { it.generatedSourceDirs }.distinct().map(::File)

    /** Top-level declarations of one generated Kotlin source. */
    fun declarations(text: String): List<String> {
        var pkg = ""
        val out = mutableListOf<String>()
        for (line in text.lines()) {
            PACKAGE.find(line)?.let { pkg = it.groupValues[1]; return@let }
            val m = DECL.find(line) ?: continue
            out += "$pkg ${m.groupValues[1].replace(Regex("\\s+"), " ")} ${m.groupValues[2]}"
        }
        return out
    }

    fun snapshot(dirs: List<File>): Set<String> = dirs.filter { it.isDirectory }.flatMap { d ->
        d.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { declarations(it.readText()) }.toList()
    }.toSortedSet()

    fun file(s: HrSettings) = File(s.workDir, "generated-declarations.txt")

    fun record(s: HrSettings) {
        val dirs = dirs(s)
        val f = file(s)
        if (dirs.isEmpty()) { f.delete(); return }
        f.parentFile.mkdirs()
        f.writeText(snapshot(dirs).joinToString("\n"))
    }

    /** The WARM reason when the generated declarations differ from the recorded ones, else null (also without a record). */
    fun changed(s: HrSettings): String? {
        val f = file(s)
        if (!f.isFile) return null
        return reason(f.readLines().filter { it.isNotBlank() }.toSet(), snapshot(dirs(s)))
    }

    fun reason(old: Set<String>, new: Set<String>): String? {
        val added = new - old
        val removed = old - new
        if (added.isEmpty() && removed.isEmpty()) return null
        fun names(xs: Set<String>) = xs.sorted().take(4).joinToString(", ") { it.substringAfterLast(' ') } + if (xs.size > 4) ", ..." else ""
        val parts = listOfNotNull(added.takeIf { it.isNotEmpty() }?.let { "added ${it.size}: ${names(it)}" },
            removed.takeIf { it.isNotEmpty() }?.let { "removed ${it.size}: ${names(it)}" })
        return "WARM generated-declarations: generated declarations changed (${parts.joinToString("; ")})"
    }
}

/**
 * #76: in modules that use project compiler plugins, a plugin may generate code from annotations on classes during the
 * compile itself, so there is no generated file to compare. What it generates can be registered at start (the same
 * case as generated declarations), so a top-level class or object whose annotations change, or an annotated one that
 * appears or disappears, is a warm restart. The snapshot is `<package> <kind> <name> @A,@B` per top-level class-like
 * declaration of those modules' own sources. No annotation or plugin is special-cased.
 */
object PluginAnnotations {
    private val PACKAGE = Regex("""^package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
    private val ANNOTATION = Regex("""@([A-Za-z_][A-Za-z0-9_.]*)""")
    private val CLASS_DECL = Regex("""^((?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s+)*)(?:(?:public|internal|private|data|sealed|abstract|open|final|enum|annotation|value|inline|expect|actual)\s+)*(class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)""")

    fun dirs(s: HrSettings): List<File> = s.modules.filter { it.compilerPlugins.isNotEmpty() || it.pluginOptions.isNotEmpty() }
        .flatMap { it.ownSourceDirs }.distinct().map(::File)

    /** Top-level class-like declarations with their annotations (column-0 `@...` lines just above, and inline ones). */
    fun declarations(text: String): List<String> {
        var pkg = ""
        val pending = mutableListOf<String>()
        val out = mutableListOf<String>()
        for (line in text.lines()) {
            PACKAGE.find(line)?.let { pkg = it.groupValues[1]; pending.clear(); return@let }
            if (line.startsWith("@") && CLASS_DECL.find(line) == null) {
                pending += ANNOTATION.findAll(line.substringBefore("//")).map { it.groupValues[1].substringAfterLast('.') }
                continue
            }
            val m = CLASS_DECL.find(line)
            if (m != null) {
                val inline = ANNOTATION.findAll(m.groupValues[1]).map { it.groupValues[1].substringAfterLast('.') }
                out += "$pkg ${m.groupValues[2]} ${m.groupValues[3]} " + (pending + inline).distinct().sorted().joinToString(",") { "@$it" }
            }
            if (line.isNotBlank() && !line.startsWith("@") && !line.startsWith("//")) pending.clear()
        }
        return out
    }

    fun snapshot(dirs: List<File>): Set<String> = dirs.filter { it.isDirectory }.flatMap { d ->
        d.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { declarations(it.readText()) }.toList()
    }.toSortedSet()

    fun file(s: HrSettings) = File(s.workDir, "plugin-annotations.txt")

    fun record(s: HrSettings) {
        val dirs = dirs(s)
        val f = file(s)
        if (dirs.isEmpty()) { f.delete(); return }
        f.parentFile.mkdirs()
        f.writeText(snapshot(dirs).joinToString("\n"))
    }

    fun changed(s: HrSettings): String? {
        val f = file(s)
        if (!f.isFile) return null
        return reason(f.readLines().filter { it.isNotBlank() }.toSet(), snapshot(dirs(s)))
    }

    /** Declarations (`pkg kind name`) whose annotation set differs, or annotated ones added/removed; null when none. */
    fun reason(old: Set<String>, new: Set<String>): String? {
        fun key(e: String) = e.split(' ').take(3).joinToString(" ")
        fun annotations(e: String) = e.split(' ').getOrElse(3) { "" }
        val o = old.associateBy(::key)
        val n = new.associateBy(::key)
        val changes = (o.keys + n.keys).sorted().mapNotNull { k ->
            val a = o[k]?.let(::annotations)
            val b = n[k]?.let(::annotations)
            val name = k.substringAfterLast(' ')
            when {
                a == b -> null
                a == null -> if (b!!.isEmpty()) null else "$name added with $b"
                b == null -> if (a.isEmpty()) null else "$name removed (had $a)"
                else -> "$name: ${a.ifEmpty { "no annotations" }} -> ${b.ifEmpty { "no annotations" }}"
            }
        }
        if (changes.isEmpty()) return null
        return "WARM compiler-plugin-annotations: annotations a compiler plugin reads changed (" +
            changes.take(4).joinToString("; ") + (if (changes.size > 4) "; ..." else "") + ")"
    }
}
