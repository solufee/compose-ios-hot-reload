# Plugin 0.2.2 (composite builds) - evidence

Toolchain **2.5.255-hotreload.6**, configuration cache on (the fixture's `gradle.properties`, as in the KMP wizard template).

Run the fixture (one command; the wizard zip only provides the Gradle wrapper):

```bash
python3 gradle-plugin/test-projects/composite/accept-composite.py --zip <KMP wizard zip> --work <empty dir> \
  --plugin-repo <maven repo with the plugin> --toolchain <TOOLCHAIN>/kotlin-native --udid <booted simulator>
```

| File | What | Result |
|---|---|---|
| `accept-composite-toolchain6.txt` | A: `includedModule` for lib-build's util and core (compiled from source; each linked exactly once; configuration cache reused); HOT in app, feature (convention plugin), core, util and core's actual, on screen without taps; WARM for core's expect signature, an expect/actual pair added/removed and core's string resource; an open Dialog updates; release check by digest. B: no declaration: lib-build's own klibs linked with the hint, core's resources bundled, a change warm-restarts. C: the plugin in lib-build (:demo) run from the root build with lib-build's local.properties (port 5569), HOT for demo and core. D: only core declared: util linked as lib-build's klib (once), core HOT, util WARM (`external-klibs`). E: a wrong build name / path fails at once with the included builds / modules listed, nothing compiled. ~/.konan/dependencies untouched; no configuration cache problems in 35 Gradle runs | 46/46 |
| `accept-composite-toolchain6-run1.txt` | first full run: the one failure was the script's check; it also showed that lib-build's own klib compiles were scheduled before a DSL error, which the plugin now skips | 45/46 |

## Regressions on the same branch (single-build users)

| File | Result |
|---|---|
| `unit-tests-toolchain6.txt` | 63/63 |
| `../../kmp-template/evidence/accept-kmp-022-toolchain6.txt` (wizard template, sources unchanged, --multi --force-recompose --dialog) | 63/63 |
| `example-gradle/accept-plugin-p1-022-tc6-*.txt` (EH, ui/app/both 20/20/3, multi-file 3, real-tap navigation 3, cold start 10/10, three modules 5/5/5 + 3, release by digest) | all OK |
| `example-gradle/accept-plugin-022-tc6-b4.txt` | 17/17 |
| `../../classifier-cases/evidence/cases-toolchain6-composite.txt` | 48/48 |
