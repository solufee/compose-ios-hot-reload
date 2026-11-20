// Included build: a KMP library build that the root app consumes as org.example.lib:core (dependency substitution).
// It resolves the hot reload plugin from its own repositories (for :demo).
rootProject.name = "lib-build"

pluginManagement {
    repositories {
        // HR_PLUGIN_REPOSITORY
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

include(":util", ":core", ":demo")
