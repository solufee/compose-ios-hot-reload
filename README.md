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
  https://github.com/solufee/compose-ios-hot-reload/releases/download/toolchain-2.5.255-hotreload.7/toolchain.json \
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
    id("com.solufee.cmp.hotreload") version "0.2.3"
}
```

**4. Point to the toolchain** in `local.properties` at the project root. `<cache>` is the `--cache` directory
from step 1, written as an absolute path:

```properties
cmp.hotreload.toolchainDir=<cache>/2.5.255-hotreload.7/kotlin-native
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
# or keep it running: a hot reload on every save (Ctrl-C to stop)
./gradlew hotReloadWatch -t
```

`hotReload` with nothing changed prints `nothing changed` and succeeds; compile errors fail the run
(in watch mode only that run, the watch keeps going).

- Function bodies in `commonMain` or `iosMain` are swapped in place (`compat: HOT`).
- Changes that cannot be hot-swapped are applied with a warm restart (`WARM …`, about 20 s), for example
  changes to signatures, class fields or `expect`/`actual` declarations, and resource-only edits.

Stop with `./gradlew hotReloadDaemonStop`.

### Choosing the simulator

By default the plugin uses the booted simulator (`cmp.hotreload.simulatorUdid=booted`). That works when exactly one
simulator is booted; with none or several, `hotReloadRun` (and a warm restart) stops and lists the booted ones.
Then pick one by UDID in `local.properties` (or `-Pcmp.hotreload.simulatorUdid=…`, or `~/.gradle/gradle.properties`):

```properties
cmp.hotreload.simulatorUdid=<UDID>
```

List UDIDs with `xcrun simctl list devices booted` (all devices: `xcrun simctl list devices`). For an iPad simulator,
set its UDID explicitly, since an iPhone simulator is usually booted as well. `hotReload` itself reaches the running app
through a port (`cmp.hotreload.port`, default 5567); give two apps running at the same time different ports.

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

- iOS simulator (arm64) only. Physical devices are not supported: the toolchain links Kotlin code into the running
  app process at run time (JIT), and at launch the whole Kotlin code is loaded that way too. A device only runs code
  from signed pages, so the debug app cannot even start there; it is not a case of "runs, but without hot reload".
  No way around code signing is provided or supported.
- Only the `iosSimulatorArm64` target is built. The Swift/Xcode layer (`iosApp/`) is not part of hot reload;
  your release and Xcode builds are unchanged.
- Compose Multiplatform up to 1.13.0-rc01 (projects on 1.13.0-beta02 or rc01 work), and libraries built with Kotlin 2.5 or earlier.
- Libraries with native code (cinterop) are not supported yet.
- iPad simulators: add `cmpHotReload { deviceFamily = listOf(1, 2) }` so the app runs at iPad size.

See [docs/LIMITATIONS.md](docs/LIMITATIONS.md) for details.

## More

- Step-by-step guide, troubleshooting and options: [docs/TRYOUT.md](docs/TRYOUT.md) (Chinese)
- Building the toolchain from source and internals: [DEVELOP.md](DEVELOP.md)
- Issues: [github.com/solufee/compose-ios-hot-reload/issues](https://github.com/solufee/compose-ios-hot-reload/issues)

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
