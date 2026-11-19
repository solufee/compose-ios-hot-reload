pluginManagement {
    // The hot-reload plugin is built from this repository; nothing is downloaded or published.
    includeBuild("../gradle-plugin")
}

rootProject.name = "cmp-hot-reload"
include("app", "ui")
