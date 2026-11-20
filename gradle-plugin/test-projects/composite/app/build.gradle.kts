plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("com.solufee.cmp.hotreload") version "HR_PLUGIN_VERSION"
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "App"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(project(":feature"))
            implementation("org.example.lib:core:1.0")       // from the included build lib-build
        }
    }
}

// HR_INCLUDED_MODULES_BEGIN
cmpHotReload {
    // Modules of the included build lib-build compiled from source, so their code hot reloads too.
    includedModule("lib-build", ":util")
    includedModule("lib-build", ":core")
}
// HR_INCLUDED_MODULES_END
