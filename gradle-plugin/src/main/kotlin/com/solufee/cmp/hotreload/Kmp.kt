package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import java.io.File
import java.util.zip.ZipFile

/**
 * Kotlin Multiplatform support (0.2.0): source sets read from the Kotlin Gradle plugin, the iOS entry of a
 * KMP app, and the dependency klibs of a module. The plugin has no compile dependency on the Kotlin Gradle
 * plugin (the user's KGP version is whatever their project uses); everything is read through reflection and
 * only read - nothing in the user's build is changed.
 */
object Kmp {
    /** The one target hot reload runs on. */
    const val TARGET_SOURCE_SET = "iosSimulatorArm64Main"
    const val DEPENDENCY_CONFIGURATION = "iosSimulatorArm64CompileKlibraries"

    /** A source set as read from KGP: name, Kotlin source directories, dependsOn names, and the directory set. */
    data class SourceSet(val name: String, val dirs: List<File>, val dependsOn: List<String>, val directorySet: Any?)

    /**
     * The source sets that make up the iosSimulatorArm64 compilation of [p], parents first
     * (commonMain -> ... -> iosMain -> iosSimulatorArm64Main), or null when [p] does not apply Kotlin
     * Multiplatform with an iosSimulatorArm64 target.
     */
    fun sourceSets(p: Project): List<SourceSet>? {
        val ext = p.extensions.findByName("kotlin") ?: return null
        val container = call(ext, "getSourceSets") as? Iterable<*> ?: return null
        val byName = container.filterNotNull().associateBy { call(it, "getName") as String }
        val leaf = byName[TARGET_SOURCE_SET] ?: return null
        val ordered = LinkedHashMap<String, SourceSet>()
        fun visit(ss: Any) {
            val name = call(ss, "getName") as String
            if (name in ordered) return
            @Suppress("UNCHECKED_CAST")
            val parents = (call(ss, "getDependsOn") as? Iterable<Any>)?.toList() ?: emptyList()
            parents.forEach(::visit)
            val dirSet = call(ss, "getKotlin")
            @Suppress("UNCHECKED_CAST")
            val dirs = (dirSet?.let { call(it, "getSrcDirs") } as? Set<File>)?.toList() ?: emptyList()
            ordered[name] = SourceSet(name, dirs, parents.map { call(it, "getName") as String }, dirSet)
        }
        visit(leaf)
        return ordered.values.toList()
    }

    private fun call(target: Any, method: String): Any? =
        target.javaClass.methods.firstOrNull { it.name == method && it.parameterCount == 0 }?.invoke(target)

    private val MAIN_VIEW_CONTROLLER = Regex("""(?m)^\s*(?:public\s+|internal\s+)?fun\s+MainViewController\s*\(""")
    private val PACKAGE = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
    private val MAIN = Regex("""(?m)^\s*fun\s+main\s*\(""")

    /**
     * The KMP template's entry: `fun MainViewController()` (returning ComposeUIViewController) as
     * `package.MainViewController`, or null when the app has its own Kotlin `main` or no such function.
     */
    fun detectIosEntry(dirs: List<File>): String? {
        val files = dirs.filter { it.isDirectory }.flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        if (files.any { MAIN.containsMatchIn(it.readText()) }) return null
        val f = files.firstOrNull { MAIN_VIEW_CONTROLLER.containsMatchIn(it.readText()) } ?: return null
        val pkg = PACKAGE.find(f.readText())?.groupValues?.get(1)
        return if (pkg.isNullOrEmpty()) "MainViewController" else "$pkg.MainViewController"
    }

    // ---------------------------------------------------------------- dependency klibs

    /** One resolved dependency: Gradle coordinates (or file name), the klib, and the direct dependency that pulled it in. */
    data class Dependency(val id: String, val file: File, val via: String? = null) {
        val label: String get() = if (via == null || via == id) id else "$id (pulled in by $via)"
    }

    /**
     * For every component of a resolved graph, the direct dependency of the root it was reached through
     * (`component|direct`), for error messages. [root] is a Gradle ResolvedComponentResult.
     */
    fun requesters(root: org.gradle.api.artifacts.result.ResolvedComponentResult): List<String> {
        val via = LinkedHashMap<String, String>()
        val queue = ArrayDeque<Pair<org.gradle.api.artifacts.result.ResolvedComponentResult, String>>()
        for (d in root.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()) {
            val c = d.selected
            queue += c to c.id.displayName
        }
        while (queue.isNotEmpty()) {
            val (c, direct) = queue.removeFirst()
            if (via.putIfAbsent(c.id.displayName, direct) != null) continue
            c.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { queue += it.selected to direct }
        }
        return via.map { (k, v) -> "$k|$v" }
    }

    /** `key=value` lines of a klib's manifest (zip or unpacked directory). */
    fun manifest(klib: File): Map<String, String> {
        val text = try {
            if (klib.isDirectory) {
                klib.walkTopDown().firstOrNull { it.isFile && it.name == "manifest" }?.readText()
            } else {
                ZipFile(klib).use { z -> z.entries().asSequence().firstOrNull { it.name.endsWith("default/manifest") }?.let { e -> z.getInputStream(e).readBytes().decodeToString() } }
            }
        } catch (e: Exception) {
            null
        } ?: return emptyMap()
        return text.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim().replace("\\:", ":") } }.toMap()
    }

    data class Selection(
        val klibs: List<String>, val replaced: List<String>, val duplicates: List<String>,
        val newer: List<String> = emptyList(), val cinterop: List<String> = emptyList(),
    )

    private val VERSION_DIR = Regex("""^\d+\.\d+.*""")

    /** Version of a toolchain klib: its Maven version folder (external-klibs/<group>/<artifact>/<version>/), else the Compose fork's. */
    private fun toolchainVersion(path: String, composeVersion: String): String =
        File(path).parentFile?.name?.takeIf { VERSION_DIR.matches(it) } ?: composeVersion

    /**
     * Klibs to link from the modules' dependencies: drops every klib whose `unique_name` the toolchain already
     * provides (Kotlin stdlib, kotlinx, Compose, AndroidX: one copy only, the toolchain's) and duplicates among
     * the dependencies. Fails when a klib's ABI or metadata is outside what the toolchain's compiler reads, or
     * when a Compose module (org.jetbrains.compose.*, androidx.compose.*) resolves to a newer version than the
     * toolchain's copy of that same module (each module against its own copy: the two Compose version schemes
     * differ; modules the toolchain does not ship are not judged). Warns about cinterop klibs (native bindings),
     * which 0.2.0 does not support.
     */
    fun selectKlibs(toolchainKlibs: List<String>, stdlib: File, deps: List<Dependency>, composeVersion: String, logger: Logger?): Selection {
        val providedVersions = toolchainKlibs.mapNotNull { p -> manifest(File(p))["unique_name"]?.let { it to toolchainVersion(p, composeVersion) } }.toMap()
        val provided = providedVersions.keys
        val stdlibManifest = manifest(stdlib)
        val abi = stdlibManifest["abi_version"]
        // The compiler also reads klib metadata one version ahead of its ABI (2.5 reads metadata up to 2.6).
        val metadata = stdlibManifest["metadata_version"]?.let { v -> v.split('.').let { p -> "${p[0]}.${(p.getOrNull(1)?.toIntOrNull() ?: 0) + 1}.0" } }
        val manifests = deps.associateWith { manifest(it.file) }
        // A Maven component can ship several klibs (its library plus cinterop klibs whose names change between
        // versions, e.g. ui-uikit-cinterop-utils -> ui-uikit-cinterop-objcUtils): when the toolchain provides any
        // klib of a component, all of that component's klibs are replaced.
        val providedComponents = deps.filter { (manifests.getValue(it)["unique_name"] ?: "") in provided }.map { it.id }.toSet()
        val problems = mutableListOf<String>()
        val kept = LinkedHashMap<String, String>()
        val replaced = mutableListOf<String>()
        val duplicates = mutableListOf<String>()
        val newer = mutableListOf<String>()
        val cinterop = mutableListOf<String>()
        for (d in deps) {
            val m = manifests.getValue(d)
            val name = m["unique_name"] ?: d.file.nameWithoutExtension
            when {
                name in provided || d.id in providedComponents -> {
                    replaced += d.id
                    val own = providedVersions[name]
                    val version = d.id.split(':').takeIf { it.size >= 3 }?.last()
                    if (own != null && version != null && compareVersions(version, own) > 0) {
                        if (isCompose(d.id)) {
                            problems += "${d.label}: Compose module $name $version is newer than the toolchain's copy ($own). " +
                                "Use Compose Multiplatform $composeVersion or older" +
                                (if (d.via != null && d.via != d.id) ", or a release of ${d.via} built against it." else ".")
                        } else {
                            newer += "${d.id.substringBeforeLast(':')} $version -> $own"
                        }
                    }
                }
                name in kept -> duplicates += d.id
                else -> {
                    kept[name] = d.file.path
                    if (m["interop"] == "true") {
                        val native = listOfNotNull(m["linkerOpts"]?.takeIf { it.isNotBlank() }?.let { "linkerOpts $it" },
                            m["staticLibraries"]?.takeIf { it.isNotBlank() }?.let { "static libraries $it" })
                        cinterop += d.label + (if (native.isEmpty()) "" else " [${native.joinToString("; ")}]")
                    }
                    val depAbi = m["abi_version"]
                    if (abi != null && depAbi != null && compareVersions(depAbi, abi) > 0) {
                        problems += "${d.label}: built for klib ABI $depAbi, the toolchain's compiler reads up to $abi. " +
                            "Use a release of this library built with Kotlin ${abi.substringBeforeLast('.')} or older."
                    }
                    if (depAbi != null && compareVersions(depAbi, MIN_ABI) < 0) {
                        problems += "${d.label}: built for klib ABI $depAbi, older than the oldest the compiler reads ($MIN_ABI, Kotlin 1.6.10). " +
                            "Use a newer release of this library."
                    }
                    val depMeta = m["metadata_version"]
                    if (metadata != null && depMeta != null && compareVersions(depMeta, metadata) > 0) {
                        problems += "${d.label}: klib metadata $depMeta is newer than the toolchain's compiler can read (up to $metadata). " +
                            "Use an older release of this library."
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("CMP hot reload: these dependencies cannot be linked with the toolchain:\n" + problems.joinToString("\n") { "  - $it" })
        }
        logger?.lifecycle("dependency klibs: ${kept.size} linked, ${replaced.size} replaced by the toolchain's copies" +
            (if (duplicates.isEmpty()) "" else ", ${duplicates.size} duplicates skipped"))
        if (newer.isNotEmpty()) {
            logger?.lifecycle("dependency klibs: hot reload compiles against the toolchain's older copies of " +
                "${newer.distinct().joinToString(", ")}; code that uses API added after those versions does not compile here")
        }
        if (cinterop.isNotEmpty()) {
            logger?.warn("CMP hot reload: warning: cinterop klibs (bindings to native code) are not supported in 0.2.0; " +
                "linking or running them may fail:\n" + cinterop.distinct().joinToString("\n") { "  - $it" })
        }
        return Selection(kept.values.toList(), replaced, duplicates, newer.distinct(), cinterop.distinct())
    }

    /** Oldest klib ABI the compiler reads (Kotlin 1.6.10). */
    const val MIN_ABI = "1.6.0"

    /** Compose modules: both version schemes (JetBrains org.jetbrains.compose.*, AndroidX androidx.compose.*). */
    fun isCompose(id: String): Boolean = id.startsWith("org.jetbrains.compose.") || id.startsWith("androidx.compose.")

    /** Semantic-ish compare: numeric parts, then a pre-release (`-beta01`) sorts before the release. */
    fun compareVersions(a: String, b: String): Int {
        fun split(v: String): Pair<List<Int>, String?> {
            val main = v.substringBefore('-')
            val pre = v.substringAfter('-', "").ifEmpty { null }
            return main.split('.').map { it.toIntOrNull() ?: 0 } to pre
        }
        val (am, ap) = split(a)
        val (bm, bp) = split(b)
        for (i in 0 until maxOf(am.size, bm.size)) {
            val c = (am.getOrElse(i) { 0 }).compareTo(bm.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            ap == bp -> 0
            ap == null -> 1
            bp == null -> -1
            else -> ap.compareTo(bp)
        }
    }

    // ---------------------------------------------------------------- generated Info.plist

    fun infoPlist(executable: String, bundleId: String): String = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        |<plist version="1.0">
        |<dict>
        |    <key>CFBundleExecutable</key><string>$executable</string>
        |    <key>CFBundleIdentifier</key><string>$bundleId</string>
        |    <key>CFBundleName</key><string>$executable</string>
        |    <key>CFBundlePackageType</key><string>APPL</string>
        |    <key>CFBundleShortVersionString</key><string>1.0</string>
        |    <key>CFBundleVersion</key><string>1</string>
        |    <key>CADisableMinimumFrameDurationOnPhone</key><true/>
        |    <key>LSRequiresIPhoneOS</key><true/>
        |    <key>MinimumOSVersion</key><string>17.0</string>
        |    <key>UIDeviceFamily</key><array><integer>1</integer></array>
        |    <key>UILaunchScreen</key><dict/>
        |</dict>
        |</plist>
        |""".trimMargin()
}
