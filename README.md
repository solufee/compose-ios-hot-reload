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
  https://github.com/solufee/compose-ios-hot-reload/releases/download/toolchain-2.5.255-hotreload.8/toolchain.json \
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
    id("com.solufee.cmp.hotreload") version "0.2.4"
}
```

**4. Point to the toolchain** in `local.properties` at the project root. `<cache>` is the `--cache` directory
from step 1, written as an absolute path:

```properties
cmp.hotreload.toolchainDir=<cache>/2.5.255-hotreload.8/kotlin-native
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
- Since 0.2.4, signature changes of top-level functions and of final members (final class, object, companion) are
  hot too: added, removed or retyped parameters, default values, return types, `expect`/`actual` functions and new
  `expect`/`actual` function pairs. Every caller is recompiled into the patch. A `@Composable` whose signature
  changed starts its own `remember` state over once (`compat: note: …`); its callers keep theirs.
- Changes that cannot be hot-swapped are applied with a warm restart (`WARM …`, about 20 s), for example
  class fields and constructors, open/override/interface members, `inline` or `suspend` functions, `expect`/`actual`
  classes, and resource-only edits.

If the app's main thread is busy (for example a long frame or a blocking call) and does not take the patch within
2 s, the patch is not applied: `hotReload` fails with `the app was busy, patch not applied`, and the next
`hotReload` (or the next save in watch mode) sends the same change again. The wait can be changed with
`cmpHotReload { mainWaitMs = 5000 }` (toolchain .8+).

Every `WARM …` reason is followed by one line of advice (`-> …`), and the run ends with a summary such as
`compat: 2 ABI changes, cannot hot-patch; warm restart took 17.1 s`.

Stop with `./gradlew hotReloadDaemonStop`.

### Changes that always warm-restart

| Change | Reason printed | How to stay hot |
|---|---|---|
| Field added, removed or reordered; constructor changed (data classes too) | `field-layout` | keep new state in a new class, or add a factory function |
| Class removed; supertypes or `open`/`abstract`/`final` changed | `class-removed`, `signature` | keep the class until the next restart |
| An open or abstract member added, removed or changed in an open/abstract class; any member of an interface, an enum or a Kotlin subclass of an ObjC class; any `override` | `signature` | add an extension or top-level function, or a new final member, instead |
| Body or signature of an existing `inline` or `suspend` function | `inline`, `suspend` | iterate on a plain function that it calls |
| Top-level property (not `const val`) added, removed or retyped | `top-level-property` | use a function (`fun limit() = 10`) or a `const val` |
| A private top-level function made non-private (or back); typealias retargeted or removed | `signature` | - |
| `expect`/`actual` class, object, interface or property added or removed | `expect-actual` | - |
| Enum entry, property without a backing field, other unanalysed declarations | `class-shape` | - |
| Compose resources (strings, images, fonts) | `compose-resources` | - |
| A klib from an included build that is not declared with `includedModule` | `external-klibs` | declare it with `includedModule(...)` |
| `@Composable` signature with `forceRecompose = false` | `signature` | keep `forceRecompose` on (the default) |

Not in this list because they are hot since 0.2.4: signature changes of top-level functions and of final members of
final classes, objects and companions (parameters, default values, return types), `expect`/`actual` functions and
`expect fun` pairs, and `@Composable` signatures while `forceRecompose` is on (the composable's own `remember` state
resets once); a top-level `const val` added or removed, a new `inline` or `suspend` function in a final class or object,
and a new final (not open, not override) member in an open, abstract or sealed class
([review](gradle-plugin/test-projects/classifier-cases/RELAXATIONS.md)).

**Not detected yet (toolchain .8 and earlier):** if you change the type or the number of `remember { }` calls inside a
composable (and nothing else in its signature), the classifier says HOT, but the composable may then read an old slot
as the new type: wrong values or a crash. After such an edit, restart the app instead of hot reloading:
`./gradlew hotReloadPackage hotReloadRun` (rebuild and reinstall; the app starts from its first screen). A fix is
planned for toolchain .9 / plugin 0.2.5.

Details and examples: [docs/TRYOUT.md](docs/TRYOUT.md), section 4.

### State across a warm restart

A warm restart reinstalls and relaunches the app, so in-memory state (`remember`, view models) is gone. To come back
where you were, the app keeps what matters in `Documents/hr-state.json` inside its sandbox:

- **write** the file whenever that state changes; its content is up to the app (any JSON or text);
- before a warm restart the plugin reads the file, and after reinstalling it puts it **back unchanged** into the new
  install's `Documents/`; the app **reads** it at startup and uses it as the initial state;
- if the file has `"page"` and `"count"` fields, the plugin also passes `--hr-state=PAGE,COUNT` and waits until the
  line with your `readyMarker` shows `page=PAGE count=COUNT`.

`warm: the app saved no state (Documents/hr-state.json)` means the file did not exist when the restart began: the app
restarts from its first screen. Nothing else is affected. A normal `hotReloadRun` reinstalls without the old file.

Minimal sample (iosMain):

```kotlin
import platform.Foundation.*

object HrState {
    private val path: String? =
        (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String)
            ?.let { "$it/hr-state.json" }

    /** The saved text, or null on a fresh install. */
    fun load(): String? = path?.let { NSString.create(contentsOfFile = it, encoding = NSUTF8StringEncoding, error = null)?.toString() }

    fun save(text: String) {
        path?.let { NSString.create(string = text).writeToFile(it, true, NSUTF8StringEncoding, null) }
    }
}

// In a composable: start from the saved value, save on every change.
// var tab by remember { mutableStateOf(HrState.load()?.toIntOrNull() ?: 0) }
// LaunchedEffect(tab) { HrState.save(tab.toString()) }
```

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
