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

    /** The Compose compiler plugin: the toolchain's own is used, never the project's (KGP's) copy. */
    private val COMPOSE_JAR = Regex("""(^|[-_])compose-compiler-plugin""")
    const val COMPOSE_PLUGIN_ID = "androidx.compose.compiler.plugins.kotlin"

    /**
     * #76: compiler plugins of a module as its Kotlin/Native compile for the hot reload target gets them: the plugin
     * classpath (not resolved here: Gradle resolves it for the tasks that consume it) and the options without Compose's.
     */
    class CompilerPlugins(val classpath: org.gradle.api.file.FileCollection?, val options: List<String>)

    /**
     * #76: the compiler plugin classpath and `plugin:<id>:<key>=<value>` options of [p]'s `compileKotlinIosSimulatorArm64`
     * (whatever the normal build passes to the Kotlin/Native compiler; only Compose is replaced by the toolchain's own).
     * Null when [p] has no such task; read through reflection (any KGP).
     */
    fun compilerPlugins(p: Project): CompilerPlugins? {
        val task = p.tasks.findByName(COMPILE_TASK) ?: return null
        val cp = call(task, "getCompilerPluginClasspath") as? org.gradle.api.file.FileCollection
        val opts = call(task, "getCompilerPluginOptions")?.let { call(it, "getArguments") } as? List<*>
        return CompilerPlugins(cp, withoutComposeOptions(opts?.map { it.toString() } ?: emptyList()))
    }

    fun withoutComposeJars(jars: List<File>): List<File> = jars.filter { !COMPOSE_JAR.containsMatchIn(it.name) }
    fun withoutComposeOptions(options: List<String>): List<String> = options.filter { !it.startsWith("plugin:$COMPOSE_PLUGIN_ID:") }

    const val COMPILE_TASK = "compileKotlinIosSimulatorArm64"

    /**
     * #98: the Kotlin Gradle plugin version the project builds with (e.g. 2.4.21), read through reflection from the
     * applied Kotlin plugin (any KGP); null when it cannot be read.
     */
    fun kotlinPluginVersion(p: Project): String? {
        for (id in listOf("org.jetbrains.kotlin.multiplatform", "org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.android")) {
            val plugin = p.plugins.findPlugin(id) ?: continue
            (runCatching { call(plugin, "getPluginVersion") }.getOrNull() as? String)?.let { return it }
            plugin.javaClass.`package`?.implementationVersion?.let { return it }
        }
        return null
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

    /**
     * Whether dependency [d] is the klib that an included build produces for its module [path] (which hot reload
     * compiles from source instead): by coordinates (`project :<build><path>`), by location (inside the module's
     * directory), or by unique_name (`<group>:<module name>` from inside that included build).
     */
    fun isOwnBuildKlib(d: Dependency, build: String, path: String, moduleDir: File, buildDir: File): Boolean {
        if (d.id == "project :$build$path") return true
        val f = d.file.absoluteFile.path
        if (f.startsWith(moduleDir.absoluteFile.path + File.separator)) return true
        val unique = manifest(d.file)["unique_name"] ?: return false
        return f.startsWith(buildDir.absoluteFile.path + File.separator) && unique.substringAfterLast(':') == path.substringAfterLast(':')
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
        /** P0/B1: components whose project version is newer than the toolchain's copy and is linked instead ("id version (toolchain v)"). */
        val newer: List<String> = emptyList(), val cinterop: List<String> = emptyList(),
        /** P0/B1: toolchain klibs left out because the project's newer copy of the same component is linked. */
        val toolchainDropped: List<String> = emptyList(),
        /** P0/B1: components that come from the toolchain although the project asked for an older version ("id version -> v"). */
        val older: List<String> = emptyList(),
    )

    private val VERSION_DIR = Regex("""^\d+\.\d+.*""")

    /**
     * The Maven version folder of a toolchain klib: external-klibs/<group>/<artifact>/<version>/<file>, or a Gradle cache
     * path <group>/<artifact>/<version>/<sha1>/<file> (a klib list edited by hand); null for the Compose fork's klibs.
     */
    private fun versionDir(path: String): File? {
        val parent = File(path).parentFile ?: return null
        if (VERSION_DIR.matches(parent.name)) return parent
        return parent.parentFile?.takeIf { VERSION_DIR.matches(it.name) && parent.name.matches(Regex("[0-9a-f]{20,40}")) }
    }

    /** Version of a toolchain klib: its Maven version folder, else the Compose fork's. */
    private fun toolchainVersion(path: String, composeVersion: String): String = versionDir(path)?.name ?: composeVersion

    /** `group:artifact` of a public toolchain klib; null for the Compose fork's klibs. */
    private fun toolchainComponent(path: String): String? {
        val artifact = versionDir(path)?.parentFile ?: return null
        val group = artifact.parentFile ?: return null
        return "${group.name}:${artifact.name}".lowercase()
    }

    /**
     * Klibs to link from the modules' dependencies (P0/B1, plugin 0.2.10). For every Maven component that the toolchain
     * also provides (same `unique_name`, or the same `group:artifact` for the toolchain's public klibs):
     *  - the Compose fork's modules (klibs of the toolchain without a Maven version folder) and every Compose module
     *    (org.jetbrains.compose.*, androidx.compose.*) come from the toolchain; a project version newer than the toolchain's
     *    copy stops the build (each module against its own copy: the two Compose version schemes differ);
     *  - any other library is linked in the newer of the two versions, as Gradle resolves it for the release build: the
     *    project's copy when it is newer (the toolchain's klibs of that component are then left out, [Selection.toolchainDropped]),
     *    otherwise the toolchain's: the toolchain's Compose modules were built against it (here hot reload and the release
     *    build, which keeps the project's older version, link different versions of that library).
     * Duplicates among the dependencies are dropped. Fails when a linked klib's ABI or metadata is outside what the
     * toolchain's compiler reads. Warns about cinterop klibs (native bindings) that do not replace a toolchain component.
     */
    fun selectKlibs(toolchainKlibs: List<String>, stdlib: File, deps: List<Dependency>, composeVersion: String, logger: Logger?): Selection {
        val providedVersions = toolchainKlibs.mapNotNull { p -> manifest(File(p))["unique_name"]?.let { it to toolchainVersion(p, composeVersion) } }.toMap()
        val provided = providedVersions.keys
        val tcByName = toolchainKlibs.mapNotNull { p -> manifest(File(p))["unique_name"]?.let { it to p } }.groupBy({ it.first }, { it.second })
        // The Compose fork's modules: toolchain klibs without a Maven version folder (compose-klibs/<module>).
        val forkNames = toolchainKlibs.filter { toolchainComponent(it) == null }.mapNotNull { manifest(File(it))["unique_name"] }
            .filter { it.startsWith("org.jetbrains.compose.") || it.startsWith("androidx.compose.") }.toSet()
        val tcComponents = toolchainKlibs.mapNotNull { p -> toolchainComponent(p)?.let { it to p } }.groupBy({ it.first }, { it.second })
        val stdlibManifest = manifest(stdlib)
        val abi = stdlibManifest["abi_version"]
        // The compiler also reads klib metadata one version ahead of its ABI (2.5 reads metadata up to 2.6).
        val metadata = stdlibManifest["metadata_version"]?.let { v -> v.split('.').let { p -> "${p[0]}.${(p.getOrNull(1)?.toIntOrNull() ?: 0) + 1}.0" } }
        val manifests = deps.associateWith { manifest(it.file) }
        fun nameOf(d: Dependency) = manifests.getValue(d)["unique_name"] ?: d.file.nameWithoutExtension
        fun componentOf(d: Dependency) = d.id.split(':').takeIf { it.size >= 3 }?.let { "${it[0]}:${it[1]}".lowercase() }
        fun versionOf(d: Dependency) = d.id.split(':').takeIf { it.size >= 3 }?.last()
        // A Maven component can ship several klibs (its library plus cinterop klibs whose names change between
        // versions, e.g. ui-uikit-cinterop-utils -> ui-uikit-cinterop-objcUtils): the decision is per component.
        val providedComponents = deps.filter { nameOf(it) in provided || componentOf(it)?.let { c -> c in tcComponents } == true }.map { it.id }.toSet()
        val byComponent = deps.filter { it.id in providedComponents }.groupBy { it.id }
        val problems = mutableListOf<String>()
        val kept = LinkedHashMap<String, String>()
        val replaced = mutableListOf<String>()
        val duplicates = mutableListOf<String>()
        val newer = mutableListOf<String>()
        val older = mutableListOf<String>()
        val cinterop = mutableListOf<String>()
        val dropped = LinkedHashSet<String>()
        val projectWins = mutableSetOf<String>()
        for ((id, ds) in byComponent) {
            val version = versionOf(ds.first())
            val tcPaths = (ds.flatMap { tcByName[nameOf(it)].orEmpty() } + componentOf(ds.first())?.let { tcComponents[it] }.orEmpty()).distinct()
            val own = tcPaths.map { toolchainVersion(it, composeVersion) }.maxWithOrNull { a, b -> compareVersions(a, b) }
            val fork = ds.any { nameOf(it) in forkNames } || isCompose(id)
            if (fork) {
                for (d in ds) {
                    val o = providedVersions[nameOf(d)]
                    if (o != null && version != null && compareVersions(version, o) > 0) {
                        problems += "${d.label}: Compose module ${nameOf(d)} $version is newer than the toolchain's copy ($o). " +
                            "Use Compose Multiplatform $composeVersion or older" +
                            (if (d.via != null && d.via != d.id) ", or a release of ${d.via} built against it." else ".")
                    }
                }
            } else if (own != null && version != null && compareVersions(version, own) > 0) {
                projectWins += id
                newer += "${id.substringBeforeLast(':')} $version (toolchain $own)"
                dropped += tcPaths
                continue
            } else if (own != null && version != null && compareVersions(version, own) < 0) {
                older += "${id.substringBeforeLast(':')} $version -> $own"
            }
            replaced += ds.map { it.id }
        }
        for (d in deps) {
            val m = manifests.getValue(d)
            val name = nameOf(d)
            when {
                d.id in providedComponents && d.id !in projectWins -> Unit
                name in kept -> duplicates += d.id
                else -> {
                    kept[name] = d.file.path
                    if (m["interop"] == "true" && d.id !in projectWins) {
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
            (if (dropped.isEmpty()) "" else ", ${dropped.size} toolchain klib(s) replaced by the project's newer copies") +
            (if (duplicates.isEmpty()) "" else ", ${duplicates.size} duplicates skipped"))
        if (newer.isNotEmpty()) {
            logger?.lifecycle("dependency klibs: the project's version is used (newer than the toolchain's, as in the release build): " +
                newer.distinct().joinToString(", "))
        }
        if (older.isNotEmpty()) {
            logger?.lifecycle("dependency klibs: the toolchain's version is used (newer than the project's; the toolchain's Compose " +
                "modules are built against it, while the release build keeps the project's version): ${older.distinct().joinToString(", ")}")
        }
        if (cinterop.isNotEmpty()) {
            logger?.warn("CMP hot reload: warning: cinterop klibs (bindings to native code) are not supported in 0.2.0; " +
                "linking or running them may fail:\n" + cinterop.distinct().joinToString("\n") { "  - $it" })
        }
        return Selection(kept.values.toList(), replaced, duplicates, newer.distinct(), cinterop.distinct(), dropped.toList(), older.distinct())
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

    /** Allowed UIDeviceFamily values: 1 iPhone, 2 iPad. */
    val DEVICE_FAMILIES = setOf(1, 2)

    /**
     * Info.plist for an app module without one (KMP template). [deviceFamily] is UIDeviceFamily: [1] iPhone (default,
     * the output is unchanged from 0.2.0), [1, 2] iPhone and iPad, [2] iPad; on an iPad simulator only a family
     * containing 2 runs the app at iPad size instead of in iPhone compatibility mode.
     */
    fun infoPlist(executable: String, bundleId: String, deviceFamily: List<Int> = listOf(1)): String = """
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
        |    <key>UIDeviceFamily</key><array>${deviceFamily.joinToString("") { "<integer>$it</integer>" }}</array>
        |    <key>UILaunchScreen</key><dict/>
        |</dict>
        |</plist>
        |""".trimMargin()
}
