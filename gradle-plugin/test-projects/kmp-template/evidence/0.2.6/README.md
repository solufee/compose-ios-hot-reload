# Plugin 0.2.6 on toolchain 2.5.255-hotreload.10 - evidence (#56)

Official toolchain .10 (manifest sha256 2eea4615...; kotlin 3fcf2ab7, compose 42e1eba6, llvm 81fa2129, libKaldo 6340abfe),
installed with `scripts/fetch-toolchain.py` from the local mirror (8 components, 27 public klibs, sha256 checked).
"Before" = the released plugin 0.2.5 (commit 654abcf) on the official toolchain .9; "after" = this branch on the official .10.
Each `warm-timing-*` run: package + launch, then 3 warm restarts caused by a library-module edit ("ui") and 3 by an
app-module edit ("app"); per-run lines 3-8, medians lines 10-11, summary line 12.

## Before / after (medians, seconds)

| Project | Edit | Warm restart | Packaging (`ms_package`) | compile | merge | embed | bundle |
|---|---|---|---|---|---|---|---|
| example app | ui | 14.8 -> **10.2** | 8.1 -> **4.1** | 3.0 -> 2.4 | 2.1 -> 0.6 | 0.9 -> 0.3 | 1.6 -> 0.4 |
| example app | app | 15.6 -> **11.3** | 8.7 -> **5.2** | 4.2 -> 3.6 | 1.7 -> 0.6 | 0.8 -> 0.3 | 1.6 -> 0.4 |
| wizard template + core | ui | 15.7 -> **11.3** | 7.4 -> **4.0** | 2.8 -> 2.2 | 2.0 -> 0.6 | 0.9 -> 0.3 | 1.5 -> 0.3 |
| wizard template + core | app | 16.3 -> **12.8** | 8.5 -> **5.4** | 4.1 -> 3.8 | 1.8 -> 0.6 | 0.8 -> 0.3 | 1.4 -> 0.3 |

The package step itself (`package timing ms=`, per-run lines 3-8) on the example: ui 3.68-3.81 s, app 4.86-5.00 s
(target ui <= 4 s; hotreload.sh on the same toolchain: ui 4.17-4.25 s, app 5.5-5.9 s, accept-bundle10.txt).
`ms_package` adds ~0.3 s of the warm restart's own work (reading the app's state file, stopping the app).
The executable is 49 MB; the ~130 MB library object is loaded from `build/hotreload/app/merged-bootstrap.libs.o`.

## libsInExecutable

`libs-in-executable.txt`: `cmpHotReload.libsInExecutable = true` on the official .10, one ui and one app warm restart OK
(lines 6-8); the executable carries the library object: 181642560 bytes (line 9). On the .10 dev view the same .app
started and hot reloaded with `merged-bootstrap.libs.o` moved away; with the default and the file gone, `hotReloadRun`
refuses with a message to repackage.

## Full regression on the official .10

| File (line) | Result |
|---|---|
| `unit-tests-toolchain10.txt` (17) | 89/89 |
| `accept-kmp-stable-keys.txt` (110; release 103) | 64/64 |
| `accept-watch.txt` (21) | 16/16 |
| `accept-busy.txt` (30) | 11/11 |
| `accept-warm.txt` (110) | 16/16 |
| `accept-composite.txt` (94; release 34) | 50/50 |
| `cases-toolchain10.txt` (96) | 93/93 |
| `accept-plugin-p1-026-tc10-*.txt`: launch EH OK (launch 15), ui 20/20, app 20/20, both 3/3 (matrix 46-48), navigation (8), cold start 10/10 (coldstart 11), three modules (three 29-32), release digest 2923d966 unchanged (release 25) | all OK |
| `accept-b4.txt` (23) | 17/17 |
| `example-accept-kmp-sh.txt` (9) | 8/8 |

The dev-view runs (stage 1/2) are in `../0.2.6-dev/`.
