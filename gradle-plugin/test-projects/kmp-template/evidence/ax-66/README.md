# #66: test scripts save and restore the simulator's accessibility keys around `axe describe-ui` - evidence

`axe describe-ui` turns on `com.apple.Accessibility ApplicationAccessibilityEnabled` and `AccessibilityEnabled`; with
them on, every app start takes ~1.7-1.8 s longer. The 9 plugin-line scripts that call describe-ui now use the shared
helper `example-gradle/tools/sim-ax-keys.sh` (#69): `save <udid>` before the main block (before the first
describe-ui), `restore <udid>` at the end of the outermost `finally` (also after a failure or `sys.exit`), and one
check that passes only when restore exits 0 (read back == value before describe-ui; absent keys are written back as NO).
`example-gradle/accept-b4.py` is not in this change (the hot-reload line changed it in #69).

Plugin 0.2.7-dev62c (= main's plugin code), official toolchain .10. `runner-log.txt` reads both keys with
`sim-ax-keys.sh show` before and after every script.

| Script | Result (line) | `ax restored` (line) | Keys before / after the run (`runner-log.txt` line) |
|---|---|---|---|
| kmp-template/accept-watch.py | 17/17 (23) | 22 | App=1 AX=1 / App=1 AX=1 (2) |
| kmp-template/accept-kmp.py | 65/65 (112) | 110 | 1/1 / 1/1 (3) |
| kmp-template/accept-busy.py | 12/12 (32) | 31 | 1/1 / 1/1 (4) |
| kmp-template/accept-warm.py | 17/17 (112) | 111 | 1/1 / 1/1 (5) |
| composite/accept-composite.py | 51/51 (96) | 95 | 1/1 / 1/1 (6) |
| kmp-template/accept-ipad.py (HR-Plugin-iPad) | 7/7 (13) | 12 | 1/1 / 1/1 (8) |
| endpoint/endpoint.py (example app) | ui 2/2, app 2/2, both 1/1, warm 1/1, taps 1/1, cold 1/1, ax 1/1 (21-27) | 19 | 1/1 / 1/1 (10) |
| classifier-cases/manual_hot.py (example ui) | 26/26 (31) | 30 | 1/1 / 1/1 (14) |
| classifier-cases/lambda_stress.py (example app) | 20/20 (25) | 24 | 1/1 / 1/1 (12) |

Each script's result count is one more than before (the restore check). manual_hot's first run (line 11) stopped at
its first edit because the runner passed the anchor with the closing quote; restore still ran in the `finally` and
passed. Rerun with the right anchor (line 14); the script's usage line now says the anchor stops before the quote.

## Revert check (`ax-revert-ipad.txt`, `accept-ipad-revert.txt`)

The shared simulator reads App=1 AX=1, so the runs above write back 1. To show that restore really reverts, the plugin
line's own iPad simulator (HR-Plugin-iPad, not shared) was set to NO first:
- line 5: both keys NO; lines 7-10: `save`, one `axe describe-ui` turns both to 1, `restore` writes 0 back (exit 0);
- lines 12-17: accept-ipad.py 7/7 with `ax restored: App=0 AX=0 (before describe-ui: App=0 AX=0)`; read before and
  after the run: App=0 AX=0 both times. The iPad simulator is left at NO and shut down.
