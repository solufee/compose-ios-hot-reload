import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Every KMP module: iOS targets, kotlinx-serialization, the Koin compiler plugin and kotlinx.rpc, with their runtime libraries. */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
            pluginManager.apply("io.insert-koin.compiler.plugin")
            pluginManager.apply("org.jetbrains.kotlinx.rpc.plugin")
            val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
            extensions.configure(KotlinMultiplatformExtension::class.java) {
                iosArm64()
                iosSimulatorArm64()
                sourceSets.getByName("commonMain").dependencies {
                    implementation(libs.findLibrary("koin-core").get())
                    implementation(libs.findLibrary("koin-annotations").get())
                    implementation(libs.findLibrary("kotlinx-serialization-json").get())
                    implementation(libs.findLibrary("kotlinx-rpc-core").get())
                }
            }
        }
    }
}
