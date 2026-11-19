import java.security.MessageDigest

plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "com.solufee.cmp"
version = "0.1.0"

gradlePlugin {
    plugins {
        create("cmpHotReload") {
            id = "com.solufee.cmp.hotreload"
            implementationClass = "com.solufee.cmp.hotreload.HotReloadPlugin"
            displayName = "Compose Multiplatform iOS hot reload"
            description = "Hot reload for Compose Multiplatform on the iOS simulator, pinned to one toolchain."
        }
    }
}

// ---- publishing -------------------------------------------------------------------------------------
// The plugin is published as a static Maven repository (jar + plugin marker) into a local directory; the
// release flow copies that directory to the public repository's GitHub Pages, where it is served without
// a token at https://solufee.github.io/compose-ios-hot-reload/maven/. Nothing here uploads anything.
//   ./gradlew -p gradle-plugin publishAllPublicationsToPagesRepository [-Pcmp.publish.pagesDir=<dir>]
val publicRepoUrl = "https://github.com/solufee/compose-ios-hot-reload"
val pagesDir = providers.gradleProperty("cmp.publish.pagesDir").map { file(it) }
    .orElse(layout.buildDirectory.dir("pages/maven").map { it.asFile })

publishing {
    repositories {
        maven {
            name = "pages"
            url = uri(pagesDir.get())
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Compose Multiplatform iOS hot reload")
            description.set("Gradle plugin for hot reload of Compose Multiplatform on the iOS simulator (experimental, pinned toolchain).")
            url.set(publicRepoUrl)
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("solufee")
                    name.set("solufee")
                }
            }
            scm {
                url.set(publicRepoUrl)
                connection.set("scm:git:$publicRepoUrl.git")
            }
        }
    }
}

// LICENSE / NOTICE of the repository go into the jar (when present).
tasks.jar {
    from(rootDir.resolve("..")) {
        include("LICENSE", "NOTICE")
        into("META-INF")
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(gradleTestKit())
}

// Low-level tools owned by the low-level line (docs/INTERFACE.md). They are shipped unchanged inside
// the plugin jar so a fresh project needs no copy of this repository; tools-manifest.properties records
// the sha256 of each file so the build log can say exactly which copy ran.
val bundledTools = mapOf(
    "merge-payloads.py" to "../example-gradle/tools/merge-payloads.py",
    "embed-merged.py" to "../example-gradle/tools/embed-merged.py",
    "HrCompileDaemon.java" to "../daemon/HrCompileDaemon.java",
    "compat-classify.py" to "../scripts/compat-classify.py",   // INTERFACE §10.1 (v7)
)
val toolsResDir = layout.buildDirectory.dir("generated/hotreload-tools")
val bundleTools = tasks.register("bundleTools") {
    val sources = bundledTools.mapValues { file(it.value) }
    inputs.files(sources.values)
    outputs.dir(toolsResDir)
    doLast {
        val out = toolsResDir.get().dir("com/solufee/cmp/hotreload/tools").asFile
        out.deleteRecursively()
        out.mkdirs()
        val manifest = StringBuilder("# sha256 of the low-level tools bundled in this plugin jar\n")
        for ((name, src) in sources.toSortedMap()) {
            val bytes = src.readBytes()
            File(out, name).writeBytes(bytes)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            manifest.append("$name=$sha\n")
        }
        File(out, "tools-manifest.properties").writeText(manifest.toString())
    }
}
sourceSets.main {
    resources.srcDir(bundleTools.map { toolsResDir.get() })
}

tasks.test {
    useJUnitPlatform()
    // Contract tests against the real HrCompileDaemon need the toolchain; they are skipped without it.
    providers.gradleProperty("cmp.hotreload.toolchainDir").orNull?.let { systemProperty("cmp.hotreload.toolchainDir", it) }
    providers.environmentVariable("CMP_HOT_RELOAD_TOOLCHAIN").orNull?.let { systemProperty("cmp.hotreload.toolchainDir", it) }
}
