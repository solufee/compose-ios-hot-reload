package com.solufee.cmp.hotreload

import org.gradle.api.logging.Logger
import java.io.File

/**
 * #98: what the hot reload build state in [HrSettings.workDir] was compiled with. When any of it changes (another plugin
 * version, another toolchain or the same toolchain directory with other contents, other compiler plugins or options, an
 * ABI switch), everything compiled before is stale: module klibs, the compiler's per-file caches, the auto-cache, the
 * merge cache, the classifier baseline and the packaged app. Contents are hashed, not paths: a toolchain unpacked
 * again into the same directory must count as a change.
 */
object BuildIdentity {
    const val FILE = "build-identity.txt"
    private const val SHA_CACHE = "build-identity-sha.cache"

    /** Kept when the state is cleared: the extracted tools (content-addressed), the release baseline the user recorded and the generated Info.plist (written at configuration). */
    private val KEEP = setOf("tools", "release", "generated", FILE, SHA_CACHE, PerfMaps.RECORD)

    /** What compiling leaves behind; without a recorded identity, any of these means state from an older plugin version. */
    private val COMPILED = listOf("klib", "ic", "auto-cache", "merge-cache", "compat", "app")

    /** The plugin version, written into the jar at build time (plugin-version.properties). */
    val pluginVersion: String by lazy {
        val p = java.util.Properties()
        BuildIdentity::class.java.getResourceAsStream("/com/solufee/cmp/hotreload/plugin-version.properties")?.use { p.load(it) }
        p.getProperty("version") ?: "unknown"
    }

    /** The identity lines (`key value`), in a fixed order. */
    fun lines(s: HrSettings, pluginVersion: String = this.pluginVersion): List<String> {
        val work = File(s.workDir)
        val manifest = File(s.dist, "toolchain.json").takeIf { it.isFile }?.let { runCatching { Toolchain.parse(it) }.getOrNull() }
        val shas = ShaCache(File(work, SHA_CACHE))
        val plugins = s.modules.flatMap { it.compilerPlugins }.distinct().sorted()
        val out = mutableListOf(
            "plugin $pluginVersion",
            "toolchainVersion ${manifest?.toolchainVersion ?: "-"}",
            "kotlin ${manifest?.kotlinVersion ?: "-"} ${manifest?.kotlinPatchSha256 ?: "-"}",
            "compose ${manifest?.composeVersion ?: "-"} ${manifest?.composePatchSha256 ?: "-"}",
            // the project's own Kotlin (KGP): another one resolves other library variants and metadata
            "projectKotlin ${s.projectKotlinVersion ?: "-"}",
            "compilerJar ${shas.sha(File(s.compilerJar))}",
            "composePlugin ${shas.sha(File(s.composePlugin))}",
            "klibList ${shas.sha(File(s.klibList))}",
            // the merge step: Kaldo keeps per-archive merge results (merge-cache) and uses LLVM's objcopy/nm, which
            // cmpHotReload.llvmHome can take from outside the toolchain
            "kaldo ${shas.sha(File(s.kaldoDylib))}",
            "llvm ${s.llvmHome?.let { h -> listOf("llvm-objcopy", "llvm-nm").joinToString(",") { shas.sha(File(h, "bin/$it")).take(16) } } ?: "-"}",
            "compilerPlugins " + plugins.joinToString(" ") { "${File(it).name}=${shas.sha(File(it)).take(16)}" }.ifEmpty { "-" },
            "pluginOptions " + s.modules.filter { it.pluginOptions.isNotEmpty() }.sortedBy { it.path }
                .joinToString(" ") { m -> "${m.path}=${m.pluginOptions.sorted().joinToString(",")}" }.ifEmpty { "-" },
            // switches that change generated code (Compose) or what the merge step produces and caches
            "abi stableKeys=${s.stableKeys}",
            "packaging keepCompactUnwind=${s.keepCompactUnwind} libsInExecutable=${s.libsInExecutable}",
        )
        shas.save()
        return out
    }

    /** sha256 of the identity lines (for the klib input record, #21). */
    fun digest(lines: List<String>): String = Tools.sha256(lines.joinToString("\n").toByteArray())

    /**
     * What changed since the recorded identity, or null when nothing did. A build directory with compiled state but no
     * record (an older plugin) counts as a change; a fresh one (only checks and caches so far) does not.
     */
    fun changes(s: HrSettings, current: List<String> = lines(s)): String? {
        val work = File(s.workDir)
        val before = File(work, FILE).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }
        val compiled = COMPILED.any { File(work, it).exists() }
        return when {
            before == current -> null
            before == null && !compiled -> null
            before == null -> "no build identity recorded (state from an older plugin version)"
            else -> {
                val old = before.associateBy { it.substringBefore(' ') }
                current.filter { old[it.substringBefore(' ')] != it }.joinToString("; ") { line ->
                    val key = line.substringBefore(' ')
                    "$key ${old[key]?.substringAfter(' ')?.take(40) ?: "-"} -> ${line.substringAfter(' ').take(40)}"
                }
            }
        }
    }

    /**
     * Before a package or a launch: on a change (see [changes]), stops this project's compile daemons and deletes the
     * compiled state (all of [HrSettings.workDir] but [KEEP]), then records the current identity. Returns the reason it
     * cleared, or null.
     */
    fun ensure(s: HrSettings, logger: Logger?, current: List<String> = lines(s), stopDaemons: () -> Unit = {}): String? {
        val work = File(s.workDir).apply { mkdirs() }
        val reason = changes(s, current)
        if (reason != null) {
            stopDaemons()
            val state = work.listFiles().orEmpty().filter { it.name !in KEEP }
            val mb = state.sumOf { f -> f.walkTopDown().filter { it.isFile }.sumOf { it.length() } } / (1024 * 1024)
            state.forEach { it.deleteRecursively() }
            logger?.lifecycle("CMP hot reload: build identity changed ($reason); cleared the compiled state ($mb MB) in ${work.path}")
        }
        File(work, FILE).writeText(current.joinToString("\n", postfix = "\n"))
        return reason
    }

    /**
     * In a run that only hot-reloads (no package, no launch): the running app was built with the recorded identity, so
     * nothing is cleared or recorded; a change is reported here and the patch is refused by [Pipeline.preparePatch].
     */
    fun report(s: HrSettings, logger: Logger?, current: List<String> = lines(s)): String? =
        changes(s, current)?.also {
            logger?.lifecycle("CMP hot reload: build identity changed since the app was packaged ($it); nothing cleared during a hot reload")
        }

    /** File sha256 by path, reused while size and mtime are unchanged (the compiler jar is ~60 MB; a stat is enough). */
    class ShaCache(private val file: File) {
        private val entries = LinkedHashMap<String, Triple<Long, Long, String>>()
        private var dirty = false

        init {
            if (file.isFile) file.readLines().forEach { l ->
                val p = l.split('\t')
                if (p.size == 4) entries[p[0]] = Triple(p[1].toLongOrNull() ?: -1, p[2].toLongOrNull() ?: -1, p[3])
            }
        }

        fun sha(f: File): String {
            if (!f.isFile) return "missing"
            val key = f.absolutePath
            val (size, mtime) = f.length() to f.lastModified()
            entries[key]?.let { (s, m, sha) -> if (s == size && m == mtime) return sha }
            val sha = Tools.sha256(f.readBytes())
            entries[key] = Triple(size, mtime, sha)
            dirty = true
            return sha
        }

        fun save() {
            if (!dirty) return
            file.parentFile?.mkdirs()
            file.writeText(entries.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k\t${v.first}\t${v.second}\t${v.third}" })
        }
    }
}
