# Plugin 0.2.4 on toolchain 2.5.255-hotreload.8 - evidence (#40)

Toolchain **2.5.255-hotreload.8** installed with `scripts/fetch-toolchain.py` from the local mirror of the reviewed dist
(`toolchain.json` sha256 4eebbf10...; 8 components, 27 public klibs from Maven, sha256 checked). The plugin pins
kotlin 4f560d86, compose 42e1eba6 (Compose 1.13.0-rc01 @ f39822e6), llvm 3e0dafa3; libKaldo 6810d16b. Plugin version 0.2.4.

| File | What | Result |
|---|---|---|
| `accept-kmp-signatures.txt` | wizard template `--multi --force-recompose --dialog --signatures`: package (release baseline recorded on .8), run, HOT/WARM changes, library modules, Dialog, forced recomposition, **#28 on .8**: top-level/final-member/`@Composable` signature changes HOT with the remember-reset note (remember 2 -> 0, caller state kept, also across modules), `@Composable` signature WARM with forceRecompose off; release digest unchanged; KMP framework task graph unchanged | 59/59 |
| `accept-busy.txt` | **#40**: main thread blocked 8 s -> `hotReload` fails with "the app was busy, patch not applied", the runtime logs `HR reload: NOT applied`, nothing applied, same PID; the next `hotReload` resends the change (not "nothing changed"); in `hotReloadWatch -t` only that run fails and the watch goes on; normal reloads applied on the main queue | 11/11 |
| `accept-warm.txt` | warm restart with advice and summary (#18), signature-only changes HOT without warm advice (#28), library klib not recompiled and the classifier's app klib reused (#21) | 16/16 |
| `accept-watch.txt` | nothing changed, compile errors, watch mode with HOT/WARM/compile error, Ctrl-C | 16/16 |
| `accept-composite.txt` | composite builds, phases A-F, release digest | 50/50 |
| `cases-toolchain8.txt` | classifier case table (#28, #20) | 93/93 |
| `accept-plugin-p1-024-tc8-*.txt` | example app: launch with EH check, ui 20/20, app 20/20, both 3/3, multi-file, navigation, cold start 10/10 (EH OK each), three modules 5/5/5 + all 3/3, release digest unchanged | all OK |
| `accept-b4.txt` | B4 through the plugin: 16 change cases + tap after warm restart. `08b-signature` (`fun fmt(x: Int)` -> `fun fmt(x: Long)`) is now expected HOT (#28); it was WARM before 0.2.4 | 17/17 |
| `example-accept-kmp-sh.txt` | the low-level KMP regression `example-gradle/accept-kmp.sh` (through `hotreload.sh`, compile daemons on a short socket dir): `ui-expect-sig` is now expected HOT (#28) and is HOT with the new value, same PID | 8/8 |
| unit tests (`gradlew test` with the .8 toolchain) | incl. `ReloadOutcomeTest` (#40) and the new launch switches in `PackagerDutiesTest` | 83/83 |
