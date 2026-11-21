// No Kotlin/Compose/serialization/Koin plugins here: they reach the build through build-logic's dependencies.
// The hot reload plugin is declared once here (apply false) and applied by id in the app module. Requested in the app
// module alone, it would give that module a plugin classloader of its own, with a second copy of the Kotlin Gradle
// plugin from build-logic (configuring :shared then fails with "SwiftPMLockTaskAggregationBuildService cannot be cast").
plugins {
    id("com.solufee.cmp.hotreload") version "@PLUGIN_VERSION@" apply false
}
