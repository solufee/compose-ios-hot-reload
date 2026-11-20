# Plugin 0.2.3 on toolchain 2.5.255-hotreload.7 - evidence (#12 and the final 0.2.3 regression)

Toolchain **2.5.255-hotreload.7** (Compose base 1.13.0-rc01, `f39822e6`), installed with `scripts/fetch-toolchain.py` from the
local mirror of the reviewed dist (`toolchain.json` sha256 329902eb...; 8 components, no bundled-klibs, 27 public klibs from Maven).
The plugin pins version 2.5.255-hotreload.7, Compose 1.13.0-rc01 @ f39822e6, patches 40c81628 / 335d7a32 / ad6137ad.

The final regression ran on the integration of all 0.2.3 work: main 4c664c8 (#7/#8 merged) + #10 (PR #17) + #12 (this PR).

## #12

| File | What | Result |
|---|---|---|
| `accept-kmp-beta02.txt` | wizard template upgraded to Compose Multiplatform **1.13.0-beta02** (sources unchanged) + `--multi --force-recompose --dialog --back-handler --adaptive`: package, run, commonMain / actual HOT, expect signature WARM, expect/actual pair WARM, library modules, Compose resource WARM, Dialog, **BackHandler** (a back event reaches the handler; a HOT edit next to it keeps the counter; a HOT edit of the handler runs on the next back event), **ComposeUIView** in the app's own view controller under the generated entry and Info.plist (renders at its own 240x60, HOT edit recomposes without taps; screenshots `beta02-adaptive-ad-*.png`), release baseline recorded on .7 and verified by digest, KMP framework task graph unchanged | 73/73 |
| `accept-kmp-beta02-run1.txt` | first run: the ComposeUIView check looked for text in the accessibility tree (ComposeUIView content is not exposed there); the task-graph check failed once and passed in every later run | 70/73 |
| `adaptive-explicit-*.png`, `adaptive-intrinsic-blank.png` | manual check on an iPad: ComposeUIView with explicit size renders and hot reloads; with only `preferredSizeReportingStrategy = IntrinsicContentSize` and no size it stayed blank (not verified whether that is Compose's own behaviour; LIMITATIONS) | - |
| `compose-rc01-package-run.txt` | the same project on Compose **1.13.0-rc01**: version check passes, package, run, EH check | OK |
| case table in `KmpTest` | beta02 and rc01 pass; 1.13.0 and 1.14.0-alpha01 (and an androidx Compose 1.13.0) fail before packaging, each module against the toolchain's own copy | in unit tests |

## Final 0.2.3 regression on .7 (single build + composite)

| File | Result |
|---|---|
| `accept-kmp-template.txt` (wizard template as generated, Compose 1.12.2, --multi --force-recompose --dialog) | 63/63 |
| `accept-composite.txt` (composite builds incl. #10's phase F) | 51/51 |
| `accept-plugin-p1-023-tc7-*.txt` (example: EH, ui/app/both 20/20/3, multi-file 3, real-tap navigation 3, cold start 10/10, three modules 5/5/5 + 3, release re-recorded on .7 and verified by digest in Gradle and `env -i`) | all OK |
| `accept-plugin-023-tc7-b4.txt` | 17/17 |
| `cases-toolchain7.txt` | 48/48 |
| unit tests of the integration (`gradlew test` on .7) | 71/71 |
| `unit-tests-branch.txt` (this branch alone) | see file |
