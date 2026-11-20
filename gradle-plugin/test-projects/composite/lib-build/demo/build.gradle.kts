// Scenario 1: the hot reload plugin applied in an included build, run from the root build
// (./gradlew :lib-build:demo:hotReloadPackage etc.).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("com.solufee.cmp.hotreload") version "HR_PLUGIN_VERSION"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(project(":core"))
        }
    }
}
