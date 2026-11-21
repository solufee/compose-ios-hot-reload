plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.serializationPlugin)
    implementation(libs.compose.compilerGradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.koin.compilerGradlePlugin)
    implementation(libs.kotlinx.rpcGradlePlugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "demo.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("kmpCompose") {
            id = "demo.kmp.compose"
            implementationClass = "KmpComposeConventionPlugin"
        }
    }
}
