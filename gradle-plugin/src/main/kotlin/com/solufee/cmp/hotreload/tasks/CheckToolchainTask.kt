package com.solufee.cmp.hotreload.tasks

import com.solufee.cmp.hotreload.HostFacts
import com.solufee.cmp.hotreload.PinnedToolchain
import com.solufee.cmp.hotreload.Proc
import com.solufee.cmp.hotreload.TOOLCHAIN_MANIFEST
import com.solufee.cmp.hotreload.Toolchain
import com.solufee.cmp.hotreload.ToolchainProblem
import com.solufee.cmp.hotreload.ToolchainValidator
import com.solufee.cmp.hotreload.Tools
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Reads toolchain.json and checks it against the pinned toolchain; also checks the host (Xcode, simulator
 * runtime) and every file the pipeline needs. Every hot-reload task depends on it. A passing result is cached
 * per manifest, Xcode installation and klib list, so the slow host probe runs only when one of them changes.
 */
abstract class CheckToolchainTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val toolchainDir: Property<String>

    @get:Input
    abstract val probeHost: Property<Boolean>

    /** DSL mistakes found at configuration time; reported here so the rest of the build is never broken. */
    @get:Input
    abstract val configErrors: ListProperty<String>

    /** Files outside toolchain.json the pipeline needs (label -> path). */
    @get:Input
    abstract val requiredFiles: MapProperty<String, String>

    @get:Internal
    abstract val cacheFile: Property<String>

    init {
        configErrors.convention(emptyList())
        requiredFiles.convention(emptyMap())
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val pinned = PinnedToolchain.current
        val errors = configErrors.get()
        if (errors.isNotEmpty()) {
            throw GradleException("CMP hot reload: cmpHotReload { } is misconfigured:\n" + errors.joinToString("\n") { "  - $it" })
        }
        val dir = toolchainDir.orNull
            ?: throw GradleException(
                "CMP hot reload: no toolchain configured.\n" +
                    "  Set one of: cmpHotReload { toolchainDir = file(\"...\") }, the property cmp.hotreload.toolchainDir " +
                    "(-P, local.properties or ~/.gradle/gradle.properties), or the environment variable CMP_HOT_RELOAD_TOOLCHAIN.\n" +
                    "  It must point at the Kotlin/Native dist that contains $TOOLCHAIN_MANIFEST (generate it with scripts/gen-toolchain.sh).",
            )
        val manifest = File(dir, TOOLCHAIN_MANIFEST)
        if (!manifest.isFile) {
            fail(null, manifest, pinned, ToolchainProblem(TOOLCHAIN_MANIFEST, "a file", "<missing>",
                "run scripts/gen-toolchain.sh with KOTLIN_DIST=$dir, or point toolchainDir at the right dist"))
        }
        val text = manifest.readText()
        val toolchain = try {
            Toolchain.parse(manifest, text)
        } catch (e: Exception) {
            fail(null, manifest, pinned, ToolchainProblem(TOOLCHAIN_MANIFEST, "valid JSON", e.message ?: e.toString(),
                "regenerate it with scripts/gen-toolchain.sh"))
        }

        val key = cacheKey(text, toolchain)
        val cache = File(cacheFile.get())
        if (probeHost.get() && cache.isFile && cache.readText() == key) {
            checkRequiredFiles()
            logger.lifecycle("CMP hot reload: toolchain OK (cached; interfaceVersion ${toolchain.interfaceVersion}, ${toolchain.xcode})")
            return
        }
        val host = if (probeHost.get()) HostFacts(xcode = probeXcode(), iosRuntimes = probeRuntimes()) else HostFacts(null, null)
        val problems = ToolchainValidator.validate(toolchain, pinned, host)
        if (problems.isNotEmpty()) {
            cache.delete()
            throw GradleException(ToolchainValidator.report(toolchain, manifest, pinned, problems))
        }
        checkRequiredFiles()
        if (probeHost.get()) {
            cache.parentFile.mkdirs()
            cache.writeText(key)
        }
        logger.lifecycle(
            "CMP hot reload: toolchain OK (interfaceVersion ${toolchain.interfaceVersion}, Kotlin ${toolchain.kotlinVersion}, " +
                "Compose ${toolchain.composeVersion}, ${host.xcode ?: toolchain.xcode})",
        )
    }

    private fun checkRequiredFiles() {
        val missing = requiredFiles.get().filterValues { !File(it).exists() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "CMP hot reload: required files are missing:\n" + missing.entries.joinToString("\n") { (label, path) ->
                    "  - $label: $path\n      fix: ${fixFor(label)}"
                },
            )
        }
    }

    private fun fail(t: Toolchain?, manifest: File, pinned: PinnedToolchain, problem: ToolchainProblem): Nothing =
        throw GradleException(ToolchainValidator.report(t, manifest, pinned, listOf(problem)))

    private fun fixFor(label: String) = when {
        "orcRuntime" in label -> "copy lib/clang/21/lib/darwin/liborc_rt_iossim.a from the LLVM build (README.md, step 1) " +
            "and set cmp.hotreload.orcRuntime, or put it at tools/liborc_rt_iossim_arm64.a"
        "objcUtilsLib" in label -> "build CMPUIKitUtils.xcodeproj from the Compose fork (README.md, step 3) " +
            "and set cmp.hotreload.objcUtilsLib, or put it at tools/libCMPUIKitObjcUtils.a"
        "Info.plist" in label -> "add an Info.plist with CFBundleExecutable and CFBundleIdentifier to the app module"
        else -> "check the module's sourceDir in cmpHotReload { }"
    }

    /** Everything a host probe depends on: manifest, plugin pin, selected Xcode, klib list. */
    private fun cacheKey(manifestText: String, t: Toolchain): String {
        val xcodeDir = Proc.run(listOf("xcode-select", "-p"), echo = false).output.trim()
        val xcodeStamp = File(xcodeDir).parentFile?.let { File(it, "Info.plist").lastModified() } ?: 0
        val klibStamp = t.klibList?.let { File(it).lastModified() } ?: 0
        val kaldoStamp = t.kaldoIossim?.let { File(it).let { f -> "${f.lastModified()}:${f.length()}" } } ?: ""
        val pinnedText = PinnedToolchain::class.java.getResourceAsStream("/com/solufee/cmp/hotreload/pinned-toolchain.json")!!
            .use { it.readBytes() }
        return Tools.sha256((manifestText + "|" + String(pinnedText) + "|" + xcodeDir + "|" + xcodeStamp + "|" + klibStamp + "|" + kaldoStamp).toByteArray())
    }

    private fun probeXcode(): String? =
        Proc.run(listOf("xcodebuild", "-version"), echo = false).takeIf { it.exit == 0 }?.output
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" ")

    private fun probeRuntimes(): List<String>? =
        Proc.run(listOf("xcrun", "simctl", "list", "runtimes"), echo = false).takeIf { it.exit == 0 }?.output?.let { text ->
            Regex("""iOS [0-9.]+""").findAll(text).map { it.value }.distinct().toList()
        }
}
