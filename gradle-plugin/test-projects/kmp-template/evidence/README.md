# Plugin 0.2.0 (Kotlin Multiplatform) - evidence

All final runs use toolchain **2.5.255-hotreload.5** (downloaded with `scripts/fetch-toolchain.py`; its 9 components are
identical to the reviewed dist, `toolchain.json` sha256 dee5d238...) on Xcode 27.2 / iOS 27.2 simulator.

| File | What | Result |
|---|---|---|
| `accept-kmp-toolchain5.txt` | `accept-kmp.py` on the official KMP wizard template (iOS only), **template sources unchanged, strict plist check on**, plus one expect/actual pair and kotlinx-datetime 0.7.1: release baseline, package, run (EH check, SELREF), real taps, commonMain body HOT, kotlinx-datetime use HOT, iosMain actual HOT, expect signature WARM and HOT again after it, add / remove an expect/actual pair WARM, three klibs with edited manifests (ABI 2.7.0, metadata 2.7.0, ABI 1.4.1) stop the build, release check by `__text` digest, KMP framework task graph unchanged | 32/32 |
| `accept-kmp-toolchain5-hashset.txt` | the same run before the release check moved to the `__text` digest (release judged by the baseline hash set) | 32/32 |
| `accept-kmp-toolchain4-run1.txt` | interim run on toolchain .4 with the plist workaround; its only failure was the script not recording a release baseline (fixed) | 30/31 |
| `unit-tests-toolchain5.txt` | plugin unit tests, including `ExpectActualCasesTest` (12-case expect/actual table: 6 warm, 6 not), the regressions for init-only objects and the pending-archive list, and `ReleaseDigestTest` (one changed code byte fails) | 53/53 |

Elsewhere in the repository:

| File | What | Result |
|---|---|---|
| `example-gradle/accept-plugin-p1-020-tc5-*.txt` | example project, full matrix through the plugin: launch (EH OK), ui 20 / app 20 / both 3, multi-file 3, real-tap navigation, cold start 10, three modules (core/ui/app 5 each, all 3) | all OK |
| `example-gradle/accept-plugin-p1-020-tc5-release.txt` | release check inside that run (baseline hash set) | unchanged |
| `example-gradle/accept-plugin-020-tc5-release-digest.txt` | release check by `__text` digest: baseline, hot-reload session, verify inside Gradle, same commands with `env -i`; raw `__text` differs between builds (GOT slot order), digest identical | unchanged |
| `example-gradle/accept-plugin-020-tc5-b4.txt` | B4 through the plugin: 16 change cases + taps after a warm restart; STUB-SEL 0 mismatch, EH check | 17/17 |
| `test-projects/classifier-cases/evidence/cases-toolchain5.txt` | classifier case table (3a) on .5 | 48/48 |
| `example-gradle/accept-plugin-p1-020-tc4-*.txt` | the same full matrix on toolchain .4 (before the switch); its release step was cut off by a network outage at 23:07 and was rerun on .5 | all OK except release (not run) |

Reproduce: `python3 accept-kmp.py --zip <wizard zip> --work <dir> --plugin-repo <maven url> --toolchain <TOOLCHAIN>/kotlin-native --udid <UDID>`
(add `--plist-workaround` only with toolchain .4). The script stops its app (by bundle id) and the project's compile daemons at the end.
