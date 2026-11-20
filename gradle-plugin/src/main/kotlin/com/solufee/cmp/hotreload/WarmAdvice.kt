package com.solufee.cmp.hotreload

/**
 * #18: one actionable sentence for every WARM reason, and the summary line of a warm restart.
 *
 * Reasons come from compat-classify.py (`WARM <category> <symbol>: <detail>`) and from the plugin itself
 * (`WARM expect-actual ...`, `WARM compose-resources: ...`, `WARM external-klibs: ...`). The table follows what the
 * classifier actually emits (see reasons_for in scripts/compat-classify.py); unknown reasons get a generic sentence.
 */
object WarmAdvice {
    data class Parsed(val category: String, val symbol: String?, val detail: String)

    fun parse(reason: String): Parsed? {
        val line = reason.trim()
        if (!line.startsWith("WARM ")) return null
        val category = line.removePrefix("WARM ").substringBefore(' ').removeSuffix(":")
        val rest = line.removePrefix("WARM ").substringAfter(' ', "")
        // `WARM compose-resources: ...` and `WARM external-klibs: ...` have no symbol.
        if (line.removePrefix("WARM ").substringBefore(' ').endsWith(":")) return Parsed(category, null, rest)
        val symbol = rest.substringBefore(": ", "").takeIf { it.isNotEmpty() }
        return Parsed(category, symbol, if (symbol != null) rest.substringAfter(": ") else rest)
    }

    const val DISPATCH = "an open or abstract class, an interface, an enum or a Kotlin subclass of an ObjC class dispatches through " +
        "tables that live objects share, so its members cannot be patched; add an extension function or a top-level function instead to stay hot"

    /** The advice for one WARM reason line. */
    fun advice(reason: String): String {
        val p = parse(reason) ?: return GENERIC
        val d = p.detail
        return when (p.category) {
            "class-removed" -> "objects of this class may still be alive in the running app; keep the class until the next restart, or accept the restart"
            "field-layout" -> when {
                d.startsWith("constructor") -> "a changed constructor changes how objects are built and laid out; to stay hot, add a top-level factory function instead of changing the constructor"
                else -> "adding or removing a field changes the instance layout, and existing objects cannot be patched; keep new state in a new class or pass it as a parameter to stay hot"
            }
            "signature" -> when {
                d.startsWith("supertypes changed") -> "live objects keep their old type information; a changed supertype list cannot be patched"
                d.startsWith("modality changed") -> "making a class open, abstract or final changes how its methods are dispatched; it cannot be patched"
                d.contains("(@Composable with forceRecompose off)") -> "with forceRecompose off, the composition still holds a restart lambda with the old parameters; " +
                    "turn forceRecompose back on (the default) and relaunch: then this change is hot and only this composable's own remember state resets once"
                d.contains("(open/override member)") -> "open and override members are called through the dispatch table of live objects; add a new final or top-level function instead to stay hot"
                d.startsWith("visibility changed") -> "a private top-level function has another symbol name than an internal or public one; switching between private and non-private needs a restart (internal <-> public stays hot)"
                d.startsWith("typealias") -> "functions that use this typealias change their signature everywhere; adding a new typealias instead stays hot"
                d.startsWith("method added") || d.startsWith("method removed") || d.startsWith("return type") || d.startsWith("signature changed") -> DISPATCH
                else -> GENERIC
            }
            "inline" -> "callers carry a copy of an inline function's body; remove `inline` while you iterate on it, or accept the restart"
            "suspend" -> "running coroutines keep the old state machine of a suspend function; move the code you are changing into a plain function that the suspend function calls"
            "top-level-property" -> "a top-level property is initialized once, when its file is first used; to stay hot, use a function instead (`fun limit() = 10`)"
            "class-shape" -> "this declaration changed in a way the classifier does not analyse (for example an enum entry, the generated members of a data class whose fields changed, or a property without a backing field), so it is not patched"
            "expect-actual" -> "a new or removed expect/actual class, object, interface or property changes a type or a global on every platform; expect/actual functions stay hot"
            "compose-resources" -> "resources are copied into the app when it is packaged, so a resource change always reinstalls the app"
            "external-klibs" -> "the app is linked against this klib as a whole; a changed klib needs a new link"
            else -> GENERIC
        }
    }

    const val GENERIC = "this change cannot be hot-patched safely"

    /** The reasons, each followed by its advice on an indented line. */
    fun annotate(reasons: List<String>): List<String> = reasons.flatMap { listOf(it, "    -> ${advice(it)}") }

    /** #18: the last line of a reload that needed a warm restart. */
    fun summary(changes: Int, warmMs: Long?, why: String? = null): String {
        val what = "$changes ABI change${if (changes == 1) "" else "s"}, cannot hot-patch"
        return "compat: " + when {
            warmMs != null -> "$what; warm restart took ${"%.1f".format(java.util.Locale.ROOT, warmMs / 1000.0)} s"
            else -> "$what; not restarted (${why ?: "warm restart off"})"
        }
    }
}
