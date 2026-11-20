// Convention plugin: a Kotlin Multiplatform library with the two iOS targets.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
}
