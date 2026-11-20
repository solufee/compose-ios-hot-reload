// Composite-build fixture for plugin 0.2.2 (accept-composite.py copies it and adds the plugin repository).
rootProject.name = "CompositeApp"

pluginManagement {
    includeBuild("build-logic")          // convention plugin (KMP iOS library)
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
}

includeBuild("lib-build")                // org.example.lib:core and :util are substituted from here
include(":app", ":feature")
