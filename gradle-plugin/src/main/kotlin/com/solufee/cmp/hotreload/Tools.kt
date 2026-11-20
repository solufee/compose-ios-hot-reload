package com.solufee.cmp.hotreload

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/** The low-level tools (merge-payloads.py, embed-merged.py, HrCompileDaemon.java, compat-classify.py) shipped in the plugin jar. */
class Tools private constructor(val dir: File, val origin: String, val shas: Map<String, String>) {
    val mergePayloads get() = File(dir, "merge-payloads.py")
    val embedMerged get() = File(dir, "embed-merged.py")
    val daemonSource get() = File(dir, "HrCompileDaemon.java")
    val compatClassify get() = File(dir, "compat-classify.py")
    val releaseTextDigest get() = File(dir, "release-text-digest.py")

    fun describe(): String = "tools from $origin (sha256 " +
        shas.toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value.take(12)}" } + ")"

    companion object {
        private const val RES = "/com/solufee/cmp/hotreload/tools/"
        val NAMES = listOf("merge-payloads.py", "embed-merged.py", "HrCompileDaemon.java", "compat-classify.py", "release-text-digest.py")

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** sha256 per tool as recorded at plugin build time. */
        fun bundledManifest(): Map<String, String> {
            val stream = Tools::class.java.getResourceAsStream(RES + "tools-manifest.properties")
                ?: throw GradleException("CMP hot reload: plugin jar has no tools-manifest.properties (broken plugin build)")
            val p = Properties().apply { stream.use { load(it) } }
            return p.stringPropertyNames().associateWith { p.getProperty(it) }
        }

        /** Extracts the bundled tools once per content hash under [workDir]/tools and verifies them. */
        fun extract(workDir: File, logger: Logger?): Tools {
            val manifest = bundledManifest()
            val key = sha256(manifest.toSortedMap().toString().toByteArray()).take(12)
            val dir = File(workDir, "tools/$key")
            dir.mkdirs()
            for (name in NAMES) {
                val expected = manifest[name] ?: throw GradleException("CMP hot reload: $name missing from tools-manifest.properties")
                val f = File(dir, name)
                if (!f.isFile || sha256(f.readBytes()) != expected) {
                    val bytes = Tools::class.java.getResourceAsStream(RES + name)?.use { it.readBytes() }
                        ?: throw GradleException("CMP hot reload: $name missing from the plugin jar")
                    if (sha256(bytes) != expected) throw GradleException("CMP hot reload: $name in the plugin jar does not match its recorded sha256")
                    f.writeBytes(bytes)
                }
            }
            return Tools(dir, "plugin jar", manifest).also { logger?.lifecycle("CMP hot reload: ${it.describe()}") }
        }
    }
}
