import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/** UI modules: everything of demo.kmp.library plus Compose Multiplatform and a static framework. */
class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("demo.kmp.library")
            pluginManager.apply("org.jetbrains.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
            extensions.configure(KotlinMultiplatformExtension::class.java) {
                targets.withType(KotlinNativeTarget::class.java).configureEach {
                    binaries.framework {
                        baseName = "Shared"
                        isStatic = true
                    }
                }
                sourceSets.getByName("commonMain").dependencies {
                    for (lib in listOf("compose-runtime", "compose-foundation", "compose-material3", "compose-ui", "androidx-lifecycle-runtimeCompose")) {
                        implementation(libs.findLibrary(lib).get())
                    }
                }
            }
        }
    }
}
