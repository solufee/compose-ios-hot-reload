# Convention-plugin project (#76)

A KMP app laid out like most multi-module apps: the Gradle plugins come from an included `build-logic` build, and the
modules apply only a convention id.

| Piece | What it does |
|---|---|
| `build-logic/convention` | `demo.kmp.library` applies KMP, kotlinx-serialization, the Koin compiler plugin (1.2.3) and kotlinx.rpc (0.10.4), adds their runtime libraries, declares the iOS targets; `demo.kmp.compose` adds Compose and the static framework. The Kotlin, Compose, serialization, Koin and rpc Gradle plugins are `implementation` dependencies of build-logic, nowhere else. |
| `:core` | `id("demo.kmp.library")`: `@Serializable SavedServer`, `@Single ServerStore`, `CoreExtra` (a definition only in steps 4 and 6), `@Module @ComponentScan CoreModule`, `@Rpc interface ServerApi` |
| `:shared` | `id("demo.kmp.compose")` + `id("com.solufee.cmp.hotreload")`: Compose UI, `@Serializable sealed interface NavKey`, `@Factory GreeterImpl`, `@KoinApplication KoinApp` over both modules |
| root `build.gradle.kts` | `id("com.solufee.cmp.hotreload") version "…" apply false`, nothing else |

The hot reload plugin must be declared once in the root build (`apply false`) and applied by id in the app module.
Requested only in the app module, it gives that module a plugin classloader of its own. That classloader holds a
second copy of the Kotlin Gradle plugin from build-logic, and configuring `:shared` fails with
`SwiftPMLockTaskAggregationBuildService$Inject_ cannot be cast to SwiftPMLockTaskAggregationBuildService` (checked: the
same build without the hot reload line, or with the root declaration, configures and compiles).

## Run

```
rsync -a <wizard KmpHr project>/ P/
python3 setup.py P --plugin-repo file://<local Maven dir> --plugin-version <V> --toolchain <toolchain>/kotlin-native --udid <simulator>
python3 run.py P --udid <simulator>
```

`run.py` steps:
1. Cold start: Koin injects across both modules and serialization round-trips.
2. UI edit: HOT, same process.
3. Add a `@Serializable` field in `:core`: WARM, the new field shows after the restart.
4. Add `@Single` in `:core`: WARM, injected after the restart.
5. Remove `@Single`: WARM, `extra=none`.
6. Add `@Single` again, then edit the definition's body.

## Status

On toolchain 2.5.255-hotreload.11 the hot reload package fails: the Koin compiler plugin generates the same synthetic
file `org/koin/plugin/hints/KoinHintsAnchor.kt` in every module, and the per-file caches of the two modules collide
(two duplicate symbols, `evidence-dup-symbols.txt`; kotlinx.rpc adds none). The fix belongs to the toolchain, not the
plugin: the compiler puts the module's unique name into the private symbols of synthetic (non-absolute-path) files and
into the per-file cache constructors. Released as 2.5.255-hotreload.11.1.

On the development toolchain 2.5.255-hotreload.11-dev77 with plugin 0.2.8-rc2 (`evidence-dev77.txt`), with no change
to the project or the plugin:

| Step | Verdict | App after the step |
|---|---|---|
| normal KGP build, hot reload package | rc=0 | both modules' hot reload klibs hold what Koin and rpc generated |
| 1 cold start | - | `greet=g1 extra=none rpc=ping server={"host":"h1","name":"n1"}/SavedServer(…) nav={"type":"…NavKey.Detail","id":3}` |
| 2 UI edit | HOT, same PID | `ui=u2` |
| 3 `@Serializable` field in `:core` | WARM (field-layout, class-shape) | `"port":443` / `port=443` |
| 3b method added to the `@Rpc` interface in `:core` | WARM (method added) | `rpc=ping,status` |
| 4 add `@Single` in `:core` | WARM (compiler-plugin-annotations) | `extra=k1` |
| 5 remove `@Single` | WARM (compiler-plugin-annotations) | `extra=none` |
| 6a add `@Single` again | WARM | `extra=k1` |
| 6b body of the `@Single` class | HOT, patch loaded, then the app ends | `ClassCastException: class org.example.core.ServerStore cannot be cast to class org.example.core.ServerStore` (`evidence-dev77-step6b-crash.txt`), known issue LIMITATIONS 21, the toolchain line's |

Summary of that run 28/30. The other failed check was in `run.py` (it looked for a `:core` log that does not exist,
because the library klib is compiled under the app module's `build/hotreload`). It now looks for the plugin output in
the klibs instead. Checked on that run's build output: `:core` holds Koin's `componentscan_org_example_core_CoreModule`
and rpc's `$rpcServiceStub`, and `:shared` holds `componentscan_org_example_kmphr_di_AppModule`.

## Issue #78 retest on the development toolchain 2.5.255-hotreload.11-dev78 (`evidence-dev78-stress.txt`)

`run.py --stress 10`, plugin 0.2.8-rc2: 111/111. Step 6b (body of the `@Single` class right after the warm restart of
6a, the crash above) is HOT in the same process with `extra=k2`. Then 10 body edits of `CoreExtra` (`:core`, `@Single`)
and 10 of `GreeterImpl` (`:shared`, `@Factory`), alternating: every one HOT, the same process (one PID for all 21), the
new value on screen each time. Group E's c4 on dev78 (`compat-koin/evidence-76/dev78-group-E.txt`): HOT, same process,
`extra=x2` (it ended the app on .11 and dev77).
