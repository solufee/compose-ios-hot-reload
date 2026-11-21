plugins {
    id("demo.kmp.compose")
    id("com.solufee.cmp.hotreload")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(project(":core"))
    }
}
