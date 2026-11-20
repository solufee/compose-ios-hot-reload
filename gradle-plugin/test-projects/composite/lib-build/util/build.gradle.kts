plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    // A source set outside the default hierarchy (#10): hot reload does not find it by itself for an included module;
    // it warns and shows the includedModule { sourceSets { } } to declare.
    applyDefaultHierarchyTemplate()
    sourceSets {
        val iosSharedMain by creating { dependsOn(commonMain.get()) }
        iosMain.get().dependsOn(iosSharedMain)
    }
}
