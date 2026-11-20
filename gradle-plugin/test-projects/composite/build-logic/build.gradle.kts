plugins {
    `kotlin-dsl`
}

dependencies {
    // compileOnly: the consuming build puts the Kotlin Gradle plugin on its classpath (root build.gradle.kts).
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
}
