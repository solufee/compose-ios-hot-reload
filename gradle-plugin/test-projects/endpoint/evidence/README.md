# End-point acceptance evidence (plugin 0.1.0)

Driver: `../endpoint.py` (reads the simulator screen through AXe accessibility labels).

| File | What |
|---|---|
| `endpoint-A-local.txt` + `-part2.txt` | copy of the independent tester's `my-app` (sources unchanged), toolchain built on this machine (layout local, binaries set by properties) |
| `endpoint-B-download.txt` + `-part2.txt` | same project, toolchain 2.5.255-hotreload.3 downloaded with `scripts/fetch-toolchain.py` from a simulated Release; only `toolchainDir` set |
| `endpoint-B-download-hr4.txt` | same project, toolchain **2.5.255-hotreload.4** (current release candidate; .3 was withdrawn) downloaded from the simulated Release; only `toolchainDir` set |
| `plugin-jar-scan.txt` | the published plugin jar and its POM/module/marker: no local paths or internal names |
| `release-smoke-0.1.0.txt` | after the public release: public repo clone, toolchain downloaded anonymously over https from the Release, plugin from the real Pages URL, fresh Gradle home, no tokens |
| `endpoint-B-hr4-011.txt`, `warm-extra-011.txt` | plugin **0.1.1** (warm-restart speed-up) on the tester's project with toolchain .4: hot reloads, warm restart + taps, cold starts; two more warm restarts |
| `plugin-jar-scan-0.1.1.txt` | the 0.1.1 jar and metadata: no local paths or internal names; bundled merge-payloads.py matches the repository |
| `template-endpoint.txt` | a project made only from the TRYOUT §3 code blocks |
| `state-check.txt` | the template's optional `HrState`: warm restart keeps page and counter |

The plugin was resolved through `pluginManagement { repositories { maven(<local Pages-layout dir>) } }`, no `includeBuild`.
