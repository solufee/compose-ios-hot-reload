# Plugin 0.2.5 on toolchain 2.5.255-hotreload.9 - evidence (#47)

Toolchain **2.5.255-hotreload.9** installed with `scripts/fetch-toolchain.py` from the local mirror of the reviewed dist
(`toolchain.json` sha256 24ed7a7c...; 8 components, 27 public klibs from Maven, sha256 checked). The plugin pins
kotlin a34772eb, compose 42e1eba6 (Compose 1.13.0-rc01 @ f39822e6), llvm 3e0dafa3; libKaldo 6810d16b. Plugin version 0.2.5.
The .9 Compose compiler knows `HotReloadStableKeys`, so every hot-reload compile gets it (release compiles never).

| File (line) | What | Result |
|---|---|---|
| `accept-kmp-stable-keys.txt` (78, 80, 81, 83; summary 110) | wizard template `--multi --force-recompose --dialog --signatures --expect-stable-keys`: a `@Composable` parameter added -> HOT, its `remember` counter **kept** (taps=2), caller's state kept (78); a `remember` added in front -> that composable starts over with correct values (80); a `remember` type Int -> Long -> starts over, works (81); `stableKeys=false` on an app packaged with it on -> refused, nothing sent (83); cross-module note "kept" (87); release digest unchanged (103); the rest of the template acceptance | 64/64 |
| `accept-busy.txt` (30) | patch NOT applied while the main thread is busy (#40) | 11/11 |
| `accept-warm.txt` (110) | warm restart with advice and summary, klib reuse (#18, #21, #28); 19.9 s, packaging 12.2 s (line 40) | 16/16 |
| `accept-watch.txt` (21) | nothing changed, compile errors, watch mode, Ctrl-C | 16/16 |
| `accept-composite.txt` (94; release 34) | composite builds A-F, release digest unchanged | 50/50 |
| `cases-toolchain9.txt` (96) | classifier case table | 93/93 |
| `accept-plugin-p1-025-tc9-*.txt` | example app: launch EH OK (launch 14), ui 20/20, app 20/20, both 3/3 (matrix 46-48), multi-file, navigation (8), cold start 10/10 (11), three modules 5/5/5 + 3/3 (three 29-32), release digest 2923d966 unchanged, same as on .8 (release 25) | all OK |
| `accept-b4.txt` (23) | B4 through the plugin; warm restarts 16.0-16.6 s, packaging 10.1-10.6 s | 17/17 |
| `example-accept-kmp-sh.txt` (9) | low-level KMP regression `example-gradle/accept-kmp.sh` through `hotreload.sh` | 8/8 |
| unit tests (`gradlew test` with the .9 toolchain), incl. `StableKeysTest` | | 87/87 |
