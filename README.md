# Compose iOS Hot Reload

Hot reload for Compose Multiplatform on the iOS simulator. Edit Kotlin code, run one command, and the running app
picks up the change without restarting. Navigation and `remember` state are kept.

> **Experimental.** Apple Silicon Macs and the iOS simulator (arm64) only, debug use only.
> The plugin works only with a pinned toolchain (patched Kotlin/Native, Compose Multiplatform and LLVM).

## Requirements

- Apple Silicon Mac
- Xcode 27.2 (27B52) with the iOS 27.2 simulator runtime, and one booted iPhone simulator
- JDK 17 or 21
- Python 3.9+ and `zstd` (`brew install zstd`)

## Install

**1. Download the toolchain** (about 155 MB; no GitHub account needed):

```bash
git clone https://github.com/solufee/compose-ios-hot-reload.git
python3 compose-ios-hot-reload/scripts/fetch-toolchain.py \
  https://github.com/solufee/compose-ios-hot-reload/releases/download/toolchain-2.5.255-hotreload.6/toolchain.json \
  --cache ~/Library/Caches/compose-ios-hot-reload/toolchains
```

**2. Add the plugin repository** to `settings.gradle.kts`, first in the list:

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://solufee.github.io/compose-ios-hot-reload/maven/") }
        // your existing google(), mavenCentral(), gradlePluginPortal()
    }
}
```

**3. Apply the plugin** to the module that contains `MainViewController()` (`shared` or `composeApp` in the
Kotlin Multiplatform wizard template):

```kotlin
plugins {
    // existing Kotlin Multiplatform and Compose plugins
    id("com.solufee.cmp.hotreload") version "0.2.2"
}
```

**4. Point to the toolchain** in `local.properties` at the project root. `<cache>` is the `--cache` directory
from step 1, written as an absolute path:

```properties
cmp.hotreload.toolchainDir=<cache>/2.5.255-hotreload.6/kotlin-native
```

Add `<module>/.hr/` and `<module>/launch.log` to `.gitignore`. No source changes are needed: source sets,
`expect`/`actual`, dependencies and the iOS entry point are detected automatically.

Check the setup with `./gradlew hotReloadCheckToolchain`.

## Use

```bash
./gradlew hotReloadPackage   # first build takes about 2 minutes
./gradlew hotReloadRun       # install and launch on the simulator
# edit your code, then:
./gradlew hotReload
```

- Function bodies in `commonMain` or `iosMain` are swapped in place (`compat: HOT`).
- Changes that cannot be hot-swapped are applied with a warm restart (`WARM …`, about 20 s), for example
  changes to signatures, class fields or `expect`/`actual` declarations, and resource-only edits.

Stop with `./gradlew hotReloadDaemonStop`.

### Composite builds (`includeBuild`)

Modules of an included build that your app depends on work without extra setup and are linked as that build's
klibs (a change there restarts the app). To hot reload them too, declare them in the app module, in dependency order:

```kotlin
cmpHotReload {
    includedModule("lib-build", ":core")   // :core of includeBuild("lib-build")
}
```

The plugin can also be applied inside an included build and run from the root build
(`./gradlew :lib-build:demo:hotReload`). See [docs/TRYOUT.md](docs/TRYOUT.md) section 8.

## Scope

- Only the `iosSimulatorArm64` target is built. The Swift/Xcode layer (`iosApp/`) is not part of hot reload;
  your release and Xcode builds are unchanged.
- Compose Multiplatform up to 1.13.0-beta01, and libraries built with Kotlin 2.5 or earlier.
- Libraries with native code (cinterop) are not supported yet.

See [docs/LIMITATIONS.md](docs/LIMITATIONS.md) for details.

## More

- Step-by-step guide, troubleshooting and options: [docs/TRYOUT.md](docs/TRYOUT.md) (Chinese)
- Building the toolchain from source and internals: [DEVELOP.md](DEVELOP.md)
- Issues: [github.com/solufee/compose-ios-hot-reload/issues](https://github.com/solufee/compose-ios-hot-reload/issues)

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
