rootProject.name = "KmpHr"

pluginManagement {
    // the conventions live in an included build, as in most multi-module apps
    includeBuild("build-logic")
    repositories {
        // CMP hot reload plugin (a local Maven directory; setup.py fills it in)
        maven { url = uri("@PLUGIN_REPO@") }
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
}

include(":shared")
include(":core")
