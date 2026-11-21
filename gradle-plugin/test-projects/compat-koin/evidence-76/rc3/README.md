# 0.2.8-rc3 on toolchain 2.5.255-hotreload.11.1 (2026-11-22 03:53–04:47)

Plugin 0.2.8-rc3 from the local Maven directory (feat/plugin-passthrough-76 @ 78d82b5: #76 passthrough, #80 patch
unwind info, pin .11.1; jar sha256 553b6b53…). Toolchain fetched with scripts/fetch-toolchain.py from the .11.1 file://
repository (manifest of the release: sha256 e811b3ac…), every component's sha256 checked. Simulator 67FC79E4.

| Run | Result | File |
|---|---|---|
| convention-plugins project (build-logic: Koin + serialization + rpc on `:core` and `:shared`), 4 steps + `--stress 10` | 111/111 (cold start, UI HOT, `@Serializable` WARM, `@Rpc` method WARM, `@Single` add/remove WARM, 6b HOT, 20 body edits HOT in one process) | convention-stress.txt |
| compat group E (single module, Koin compiler plugin) | all steps; c4 (body of the Koin singleton) HOT, same process, `extra=x2` (#78 fixed) | group-E.txt |
| accept-exceptions (thrown and caught in patched code) | 6/6 (#80 fixed in the plugin) | accept-exceptions.txt |
| accept-watch / kmp / busy / warm / composite | 17/17, 65/65, 12/12, 17/17, 51/51 | accept-*.txt |
| accept-relaunch (example-gradle, plugin from source, local.properties pointed at .11.1 for the run) | 28/28 | accept-relaunch.txt |
| hotReloadVerifyRelease on the wizard template: baseline with 0.2.8-rc2 on .11, check with rc3 on .11.1 | PASS: 70245 symbols, 42 section sizes, `__text` digest 2ff7600b9825 identical (raw `__text` b1a94a4456b2 -> c25bc0159b4f, recorded only) | release-baseline-11.txt, release-verify-11-1.txt |
| unit tests on .11.1 | 114/114 | - |
