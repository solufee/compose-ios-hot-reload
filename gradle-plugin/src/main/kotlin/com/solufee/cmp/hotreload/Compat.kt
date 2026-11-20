package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Compatibility classifier (INTERFACE §10, v8). The plugin does not classify anything itself: it runs the
 * low-level `compat-classify.py` (shipped in the plugin jar) and follows its exit code — 0 hot, 3 warm restart,
 * anything else = tool error, nothing may be sent.
 *
 * v8 usage: one `baseline` / `check` process for all modules, klib dumps inside the plugin's klib daemon
 * (`--daemon`, falls back to the klib tool), snapshots cached by klib sha256 (`--cache-dir`). `check` runs in
 * the background while the patch is compiled; the patch is sent only after a HOT verdict.
 *
 * Files under build/hotreload/compat: `<module>.json` is the baseline (what the running app was built or last
 * patched from), `<module>.new.json` the candidate written by `check`; `app.klib` the app compiled as a library;
 * `app.stamp` the time of the app sources that app.json describes; `cache/` the snapshot cache.
 */
/**
 * Warm-restart reason for Compose resources (strings, drawables, fonts) that changed since the app was packaged:
 * they are copied into the .app at package time, so only a repackage (warm restart) shows them.
 */
fun resourceReasons(changed: List<String>): List<String> =
    if (changed.isEmpty()) emptyList()
    else listOf("WARM compose-resources: ${changed.size} file(s) changed since packaging (${changed.take(3).joinToString()}" +
        (if (changed.size > 3) ", ..." else "") + "); resources are copied into the app when it is packaged")

/**
 * Warm-restart reasons for expect/actual pairs added or removed between two sets of `expect` declarations
 * ("kind name", from [expectDeclarations]). Only the presence of a pair is judged here; a changed signature
 * is caught by the classifier (compat-classify.py) like any other signature change.
 */
fun expectActualReasons(before: Set<String>, now: Set<String>): List<String> =
    (now - before).sorted().map { "WARM expect-actual ${it.substringAfter(' ')}: expect ${it.substringBefore(' ')} added" } +
        (before - now).sorted().map { "WARM expect-actual ${it.substringAfter(' ')}: expect ${it.substringBefore(' ')} removed" }

private val MODS = "(?:(?:public|internal|private|protected|open|abstract|sealed|data|inline|value|annotation|enum|inner|fun|suspend|external|infix|operator|tailrec)\\s+)*"
private val EXPECT_DECL = Regex("""(?m)^\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*""" + MODS + """expect\s+""" + MODS +
    """(fun|val|var|class|object|interface|typealias)\s+(?:<[^>]*>\s*)?(?:\S*\.)?(\w+)""")

/** "kind name" of every `expect` declaration in the Kotlin files under [dir]. */
fun expectDeclarations(dir: File): Set<String> =
    if (!dir.isDirectory) emptySet()
    else dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
        .flatMap { f -> EXPECT_DECL.findAll(f.readText()).map { "${it.groupValues[1]} ${it.groupValues[2]}" } }.toSet()

class Compat(private val s: HrSettings, private val logger: Logger, private val pipeline: Pipeline, private val tools: Tools) {
    enum class Verdict { HOT, WARM }

    data class Result(val verdict: Verdict, val reasons: List<String>, val ms: Long)

    private val dir get() = s.compatDir
    private fun base(m: HrModule) = File(dir, "${m.label}.json")
    private fun candidate(m: HrModule) = File(dir, "${m.label}.new.json")
    private val appStamp get() = File(dir, "app.stamp")

    fun hasBaseline(): Boolean = s.modules.all { base(it).isFile }

    /** expect declarations of every module ("kind name"), from the sources: klib metadata keeps no expect/actual marker. */
    private val expectFile get() = File(dir, "expect-declarations.txt")
    fun expectDeclarations(): Set<String> = s.modules.flatMap { m -> m.sourceDirs.flatMap { expectDeclarations(File(it)) } }.toSortedSet()

    /** Writes the baseline for what `hotReloadPackage` just built (§10.2: at the end of package). */
    fun baseline(daemons: Daemons?) {
        dir.mkdirs()
        val t0 = System.currentTimeMillis()
        val appKlib = pipeline.compileAppKlib(daemons)
        val modules = s.libraries.map { it.label to s.klibOf(it).path } + (s.app.label to appKlib.path)
        val r = Proc.run(command("baseline", modules, daemons), env = env(), echo = false)
        if (r.exit != 0) {
            throw GradleException("CMP hot reload: compat-classify baseline failed (exit ${r.exit}):\n${r.output.trim().takeLast(3000)}")
        }
        appStamp.writeText("")
        expectFile.writeText(expectDeclarations().joinToString("\n"))
        s.modules.forEach { candidate(it).delete() }
        logger.lifecycle("compat: baseline written for ${s.modules.joinToString(",") { it.label }} (${System.currentTimeMillis() - t0} ms)")
    }

    /**
     * Starts `check` in the background. Libraries are passed as their current klibs (an unchanged klib is
     * recognised by sha256 and not dumped); the app is recompiled as a library only when its sources changed
     * since app.stamp, otherwise it is passed as `app=-` (unchanged).
     */
    fun startCheck(daemons: Daemons?): CompletableFuture<Result> = CompletableFuture.supplyAsync {
        val t0 = System.currentTimeMillis()
        val app = if (appSourcesChanged()) pipeline.compileAppKlib(daemons).path else "-"
        val modules = s.libraries.map { it.label to s.klibOf(it).path } + (s.app.label to app)
        val r = Proc.run(command("check", modules, daemons), env = env(), echo = false)
        val ms = System.currentTimeMillis() - t0
        // An expect/actual pair added or removed: warm restart (Kotlin Multiplatform, 0.2.0).
        val expectReasons = (if (expectFile.isFile) {
            expectActualReasons(expectFile.readLines().filter { it.isNotBlank() }.toSet(), expectDeclarations())
        } else emptyList()) + resourceReasons(pipeline.resourceChanges())
        when (r.exit) {
            0 -> if (expectReasons.isEmpty()) Result(Verdict.HOT, emptyList(), ms) else Result(Verdict.WARM, expectReasons, ms)
            3 -> Result(Verdict.WARM, r.output.lines().filter { it.startsWith("WARM ") } + expectReasons, ms)
            else -> throw GradleException(
                "CMP hot reload: compat-classify check failed (exit ${r.exit}); patch not sent:\n${r.output.trim().takeLast(3000)}",
            )
        }
    }

    /** Waits for [startCheck]; a tool error surfaces as the GradleException it raised. */
    fun await(pending: CompletableFuture<Result>): Result = try {
        pending.get()
    } catch (e: ExecutionException) {
        throw (e.cause as? GradleException) ?: GradleException("CMP hot reload: classifier failed; patch not sent: ${e.cause}", e.cause)
    }

    /** After a hot patch was sent: the candidates become the baseline of the running app. */
    fun commit() {
        for (m in s.modules) candidate(m).takeIf { it.isFile }?.let { f -> f.copyTo(base(m), overwrite = true); f.delete() }
        appStamp.writeText("")
        expectFile.writeText(expectDeclarations().joinToString("\n"))
    }

    private fun appSourcesChanged(): Boolean {
        val stamp = appStamp
        if (!stamp.isFile) return true
        return s.app.sourceDirs.any { d -> File(d).walkTopDown().any { it.isFile && it.extension == "kt" && it.lastModified() > stamp.lastModified() } }
    }

    private fun env() = mapOf("JAVA_HOME" to s.javaHome)

    private fun command(sub: String, modules: List<Pair<String, String>>, daemons: Daemons?): List<String> =
        listOf("python3", "-I", tools.compatClassify.path, sub, "--baseline-dir", dir.path) +
            modules.flatMap { (name, klib) -> listOf("--module", "$name=$klib") } +
            listOf("--klib-tool", s.klibTool, "--cache-dir", File(dir, "cache").path) +
            (if (daemons != null) listOf("--daemon", s.klibSocket) else emptyList())

    /** One module, v7 style (`snapshot` + `classify`); kept for the contract tests. */
    fun classify(old: File, new: File): List<String> =
        listOf("python3", "-I", tools.compatClassify.path, "classify", "--old", old.path, "--new", new.path)
}
