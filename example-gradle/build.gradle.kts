plugins {
    id("com.solufee.cmp.hotreload")
}

// The toolchain location is machine-specific, so it is not written here: put
//   cmp.hotreload.toolchainDir=<KOTLIN_NATIVE_DIST>
// into local.properties (git-ignored) or ~/.gradle/gradle.properties.
cmpHotReload {
    app(":app")
    library(":ui")
    simulator {
        readyMarker = "RENDER_APP"   // printed by the first frame of this example
    }
}
