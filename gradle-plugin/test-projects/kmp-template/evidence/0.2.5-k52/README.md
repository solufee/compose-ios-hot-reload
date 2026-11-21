# #52: the plugin's packaging uses the #32 speed-up (toolchain .9) - evidence

Official toolchain 2.5.255-hotreload.9 (manifest sha256 24ed7a7c...). "Before" = main 7add617 (plugin 0.2.5 as merged in
PR #48); "after" = branch feat/052-split-payloads. Each run: package + launch, then 3 warm restarts caused by a library
module edit ("ui") and 3 by an app module edit ("app"), each adding a top-level property (`warm-timing.py`).

## Before / after (medians, seconds; per-run lines 3-8, medians lines 10-11 of each file)

| Project | Edit | Warm restart | Packaging (`WARM OK ms_package`) | compile | merge | embed+bundle |
|---|---|---|---|---|---|---|
| example app | ui | 18.1 -> **13.6** | 11.3 -> **7.0** | 5.3 -> 2.6 | 2.7 -> 1.7 | 2.2 -> 2.4 (embed 0.9, bundle 1.5) |
| example app | app | 18.7 -> **15.0** | 12.0 -> **8.5** | 6.6 -> 4.1 | 2.7 -> 1.6 | 2.2 -> 2.5 |
| wizard template + core | ui | 19.0 -> **15.1** | 11.1 -> **7.1** | 5.2 -> 2.7 | 2.7 -> 1.8 | 2.1 -> 2.3 |
| wizard template + core | app | 19.7 -> **17.5** | 11.9 -> **8.6** | 6.7 -> 4.2 | 2.7 -> 1.7 | 2.1 -> 2.3 |

Files: `warm-timing-before-example.txt`, `warm-timing-after-example.txt`, `warm-timing-before-template.txt`,
`warm-timing-after-template.txt`. Line 2 of the "after" files shows `payload list (#32): on (N payloads read from disk)`.

The package step itself (`package timing ms=`) after: example ui 6.6-6.8 s, app 8.0-8.3 s, on par with `hotreload.sh`
(example-gradle/accept-warm-32.txt: 6.6-6.7 s, 8.2-8.3 s). `ms_package` adds ~0.3 s of the warm restart's own work
(reading the app's state file, stopping the app). The classifier baseline now runs beside merge/embed/bundle: before it
added ~0.4-1.0 s after the bundle; now `baseline-wait=0`.

## Full regression on .9 with this branch

| File (line) | Result |
|---|---|
| `accept-kmp-stable-keys.txt` (110; release 103) | 64/64 |
| `accept-busy.txt` (30) | 11/11 |
| `accept-warm.txt` (110) | 16/16 |
| `accept-watch.txt` (21) | 16/16 |
| `accept-composite.txt` (94; release 34) | 50/50 |
| `cases-toolchain9.txt` (96) | 93/93 |
| `accept-plugin-p1-025-k52-*.txt`: launch EH OK (launch 14), ui 20/20, app 20/20, both 3/3 (matrix 46-48), navigation (8), cold start 10/10 (coldstart 11), three modules (three 29-32), release digest 2923d966 unchanged (release 25) | all OK |
| `accept-b4.txt` (23); warm restarts there: packaging 7.2-7.7 s | 17/17 |
| `example-accept-kmp-sh.txt` (9) | 8/8 |
| unit tests (`gradlew test` on .9), incl. the #52 case in `StableKeysTest` (option detected in the compiler jar; only the package compile gets it; never release) | 88/88 |
