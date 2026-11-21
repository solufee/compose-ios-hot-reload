# Plugin 0.2.5 (#47) on the toolchain .9 development view - evidence

Not the final .9: the installed toolchain .8 with only the Compose compiler plugin jar replaced by the #29 build
(`compiler-2.5.255-SNAPSHOT-shadow.jar` sha256 7578ca94..., knows `HotReloadStableKeys`). The final pin, the full regression
and the release follow on the .9 manifest (after #32).

| File | What | Result |
|---|---|---|
| `accept-kmp-stable-keys.txt` | wizard template `--multi --force-recompose --signatures --expect-stable-keys`: a `@Composable` parameter added -> HOT and its `remember` counter **kept** (taps=2, on .8 it reset to 0), the caller's state kept, the note says "its remember state is kept"; a `remember` added in front (count changed) -> HOT, that composable starts over with correct values (taps=0, `extra-1`); a `remember` type changed (Int -> Long state) -> HOT, starts over (taps=0), the counter works; `hotReload -Pcmp.hotreload.stableKeys=false` on an app packaged with stable keys on is refused, nothing sent; cross-module `@Composable` with the "kept" note; the rest of the template acceptance; release baseline and check: `__text` digest unchanged (release builds never get the flag) | 60/60 |
| `StableKeysTest` (4) + all unit tests on .8 | flag detected in the plugin jar; present in library, app-klib and program args when on; absent when off or unsupported (.8); never in the release commands; HOT note text | 87/87 |
