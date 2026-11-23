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
  https://github.com/solufee/compose-ios-hot-reload/releases/download/toolchain-2.5.255-hotreload.11.2/toolchain.json \
  --cache ~/Library/Caches/compose-ios-hot-reload/toolchains
```

**2. Add the plugin repository** to `settings.gradle.kts`, first in the list (only the plugin's own group is looked up there):

```kotlin
pluginManagement {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri("https://solufee.github.io/compose-ios-hot-reload/maven/") } }
            filter { includeGroupByRegex("com\\.solufee\\.cmp(\\..*)?") }
        }
        // your existing google(), mavenCentral(), gradlePluginPortal()
    }
}
```

The filter matters: for a path it does not have, GitHub Pages sometimes answers 5xx instead of 404, and Gradle then
gives up instead of asking the next repository, so other plugins (Android, Kotlin) would fail at random.

**3. Apply the plugin** to the module that contains `MainViewController()` (`shared` or `composeApp` in the
Kotlin Multiplatform wizard template):

```kotlin
plugins {
    // existing Kotlin Multiplatform and Compose plugins
    id("com.solufee.cmp.hotreload") version "0.2.11"
}
```

**4. Point to the toolchain** in `local.properties` at the project root. `<cache>` is the `--cache` directory
from step 1, written as an absolute path:

```properties
cmp.hotreload.toolchainDir=<cache>/2.5.255-hotreload.11.2/kotlin-native
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

**Saves in a row feel slow? Check `~/.gradle/kotlin-profile`.** This only matters when that directory is large. The
Kotlin Gradle plugin keeps build statistics there (one directory shared by all your Kotlin builds, never pruned) and
goes through all of them at the end of every build. To see how many files it holds:

```bash
find ~/.gradle/kotlin-profile -maxdepth 1 -type f | wc -l
```

With a few thousand files you will not notice anything. With tens of thousands (30,000 on one of our machines) Gradle
stays busy for 5–10 s after each hot reload: the patch is already on screen, but the next save waits. In that case
either switch the statistics off in `gradle.properties` (the project's or `~/.gradle/gradle.properties`):

```properties
kotlin.internal.collectFUSMetrics=false
```

or delete the old files in `~/.gradle/kotlin-profile` yourself. Since 0.2.11, `hotReloadRun` and `hotReloadWatch` print
a warning once when that directory holds more than 5000 files and the property is not `false`; the plugin changes
neither the directory nor the property.

- Function bodies in `commonMain` or `iosMain` are swapped in place (`compat: HOT`).
- Since 0.2.4, signature changes of top-level functions and of final members (final class, object, companion) are
  hot too: added, removed or retyped parameters, default values, return types, `expect`/`actual` functions and new
  `expect`/`actual` function pairs. Every caller is recompiled into the patch. A `@Composable` whose signature
  changed starts its own `remember` state over once (`compat: note: …`); its callers keep theirs.
- Changes that cannot be hot-swapped are applied with a warm restart (`WARM …`; plugin 0.2.6 with toolchain .10:
  about 10 s after an edit in a library module and 11.5 s after an edit in the app module on the example app, of which
  repackaging takes about 4 s and 5 s; about 11.5 s and 12.5 s on the KMP wizard template; relaunching the app
  occasionally takes ~5 s longer), for example
  class fields and constructors, open/override/interface members, `inline` or `suspend` functions, `expect`/`actual`
  classes, and resource-only edits.

If the app's main thread is busy (for example a long frame or a blocking call) and does not take the patch within
2 s, the patch is not applied: `hotReload` fails with `the app was busy, patch not applied`, and the next
`hotReload` (or the next save in watch mode) sends the same change again. The wait can be changed with
`cmpHotReload { mainWaitMs = 5000 }` (toolchain .8+).

With toolchain .10 the hot-reload `.app` loads its large library object (~130 MB) from a file under
`build/hotreload/app` at launch instead of carrying it (faster packaging), so it only runs on this Mac and needs
repackaging after `build/hotreload` is deleted. For a self-contained `.app` set `cmpHotReload { libsInExecutable = true }`.

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
`expect fun` pairs, and `@Composable` signatures while `forceRecompose` is on (from plugin 0.2.5 with toolchain .9 the
composable keeps its `remember` state; before, it reset once); a top-level `const val` added or removed, a new `inline` or `suspend` function in a final class or object,
and a new final (not open, not override) member in an open, abstract or sealed class
([review](gradle-plugin/test-projects/classifier-cases/RELAXATIONS.md)).

**`remember` and signature changes (plugin 0.2.5, toolchain .9):** hot-reload builds compile composables with stable
group keys (the Compose compiler flag `HotReloadStableKeys`, set by the plugin; never in release builds). A composable
keeps its `remember` state when its parameters change; when the type, number or order of its `remember { }` calls
changes, that composable starts over cleanly. Turn it off with `cmpHotReload { stableKeys = false }` (then repackage).
With toolchain .8 or earlier the flag does not exist: a changed `remember` type or count is not detected and the old
slot may be read as the new type (wrong values or a crash), so after such an edit use
`./gradlew hotReloadPackage hotReloadRun` (rebuild and reinstall; the app starts from its first screen).

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

Since plugin 0.2.7, a warm restart usually does not reinstall: when the new build differs from the installed app only in
its executable, the plugin replaces the executable in the installed app (checked byte for byte; any other difference
reinstalls). The app's data (Documents, preferences) then stays as it is, like an app update. If the app was
uninstalled or its data container deleted, the warm restart installs it afresh. With toolchain .11, the app counts as
started once it has drawn its first frame (`HR READY first-frame`, docs/INTERFACE.md §6.1) instead of 3 s after Kotlin
main started; the wizard template's warm restart is ~1.3 s shorter.

Since plugin 0.2.8, the hot reload compiles use the Kotlin compiler plugins your build applies to the iOS compile
(kotlinx-serialization, the Koin compiler plugin, ...), and KSP output is compiled too. Adding or removing a Koin
definition (an annotation such as `@Single`, or a KSP-generated definition) restarts the app (warm restart), because the
DI container is built at start; edits inside classes stay hot.

Since plugin 0.2.9 (toolchain .11.2), names defined more than once no longer stop the package with `duplicate symbol`:
the same name in several libraries (for example what the Koin compiler plugin generates in each module) and local
classes or lambdas with the same name in two files of one package (for example `module$1` of two Koin `@Module`
classes) are both handled. The package log lists what it did with each such name.

Since plugin 0.2.9, upgrading the plugin or the toolchain needs no `hotReloadClean`: the next package notices the change
(`build identity changed (...)`), clears what the old version compiled and builds from scratch once. A plain
`hotReload` after such a change sends nothing and asks for `hotReloadPackage hotReloadRun`.

Since plugin 0.2.10, a library that is newer in your project than in the toolchain is no longer swapped for the
toolchain's older copy. Up to 0.2.9, for example, kotlinx-serialization 1.12.0 was hot-reload-linked as the toolchain's
1.7.3, and the app could crash at start with `IrLinkageError` (or a `@Serializable` class with an `Instant` field did not
compile). Now each library is linked at the newer of your version and the toolchain's, as in your release build; only
the Compose modules always come from the toolchain. The first `hotReloadPackage` after upgrading to 0.2.10 compiles
everything once, about as long as a cold package (124 s on a Koin + serialization sample project); later packages are
as fast as before. One difference remains: a library that is *older* in your project than in the toolchain is linked
at the toolchain's version (docs/LIMITATIONS.md item 16).

Since plugin 0.2.10, the app no longer writes a perf map by default. Up to 0.2.9 every app start under hot reload wrote
`/tmp/perf-<pid>.map` (about 70 MB each, never deleted). Turn it on only when you need hot-reloaded frames named in
Instruments or lldb: `cmpHotReload { perfMap = true }` or `-Pcmp.hotreload.perfMap=true`; the plugin then deletes the
maps of the app processes it started once they have exited. Maps left by 0.2.9 and earlier are not removed for you; to
delete yours that are more than 7 days old:

```bash
find /tmp -maxdepth 1 -type f -name 'perf-*.map' -user "$USER" -mtime +7 -print -delete
```

Since plugin 0.2.11, the compile daemons no longer fill `$TMPDIR` with `included<digits>` directories. Up to 0.2.10 every
compile that linked left one (about 120 MB each: the static libraries Kotlin/Native unpacks for a link, deleted only when
the daemon exits). Each daemon now has a temp directory of its own and removes what each compile unpacked there. The
plugin does not touch what older versions left in `$TMPDIR`; to delete yours that are more than a day old:

```bash
find "$TMPDIR" -maxdepth 1 -type d -name 'included*' -user "$USER" -mmin +1440 -print -exec rm -rf {} +
```

Since plugin 0.2.11, the hint for a module of an included build (composite build) can be copied as it is. With Gradle
9.6 and later it used to print a quote as the build name (`includedModule("'", ":gooze:gooze-widget'")`); it now names
the build and the module path, for example `cmpHotReload { includedModule("gooze", ":gooze-widget") }`. The
`dependency klibs:` lines are printed by every package, and by `hotReload` / `hotReloadWatch` only when they changed.
The `changed objects:` line now counts an edit in a library module for that module (it used to count a Compose
file's objects for the app).

Since plugin 0.2.11, the setup example keeps the plugin repository to the plugin's own group (`exclusiveContent`,
step 2 above). GitHub Pages sometimes answers 5xx instead of 404 for a path it does not have, and Gradle then stops
instead of asking the next repository, so other plugins (Android, Kotlin) failed to resolve at random. If you set up
the repository with an earlier README, change it to the form in step 2.

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
