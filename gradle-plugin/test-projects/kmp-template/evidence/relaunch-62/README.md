# #62: faster relaunch in the warm restart - evidence (plugin 0.2.7-dev, toolchain .10)

Official toolchain .10 (manifest sha256 2eea4615...). Timing with the #60 method
(`example-gradle/tools/relaunch-timing/relaunch-template.py`, xcrun shim + app log timeline): 3 warm restarts caused by a
library-module edit (ui1-3) and 3 by an app-module edit (app1-3); per-run lines 3-8, medians from line 12,
`ms_relaunch` median on line 30. Nothing else ran on the machine during the timing runs.

What changed:
1. In-place update: when the new bundle differs from the installed one only in its executable, the new executable is
   written next to the installed one, renamed over it atomically and checked byte for byte; no uninstall + install,
   the data container stays. Any other difference, a failed check, an uninstalled app or a deleted data container:
   uninstall + install as before (the state file is written into the new install). `hotReloadRun` still installs
   afresh. The installed path is remembered in `build/hotreload/installed-app.txt` (used only while it is still that
   device's bundle).
2. Event-driven readiness: the launch log is followed by a small python3 kqueue helper (unbuffered) instead of being
   read every 0.2 s. (`tail -F` buffered its output into the pipe: a quiet app's last line could wait there.)
3. Ready marker: not in this PR (waits for the INTERFACE format); the 3 s rule stays as the fallback.
4. Accessibility note: when the simulator reads `com.apple.Accessibility ApplicationAccessibilityEnabled` = 1, one line
   per `hotReloadRun` / warm restart says it costs ~1.7 s per start and how to turn it off. Read only.

## Relaunch (ms_relaunch, ms, median)

| Project | Before (0.2.6) | After | Removed steps |
|---|---|---|---|
| example app, ui edits | 5702 (`relaunch-before-example.txt` 3-5) | 4962 (`relaunch-after-example.txt` 3-5); final code 4785 (`relaunch-final-example.txt` 3-5) | |
| example app, all 6 | 5681 (line 30) | 4948 (line 30); final code 4959 (`relaunch-final-example.txt` 30) | uninstall 169 + install 248 + gap 182 (lines 14-16); `frame_to_detected` 250 -> 68 (line 25) |
| wizard template + core, all 6 | 6767 (`relaunch-before-template.txt` 30) | 6219 (`relaunch-after-template.txt` 30) | uninstall 166 + install 252 (lines 14-15) |

The template has no ready marker, so its readiness is still the 3 s rule (`kotlin_main_to_ready` ~3.17 s, line 26);
item 3 removes most of that.

## State and install paths (`accept-relaunch.txt`, `example-gradle/accept-relaunch.py`)

28/28 (summary line 37), example app, simulator reads `ApplicationAccessibilityEnabled` = 1:

| Section (lines) | What is proved |
|---|---|
| 1 (2-4) | `hotReloadRun --args=--hr-state=2,3`; the app writes Documents/hr-state.json `{"page":2,"count":3}` |
| 2 in place (5-10) | "executable replaced in the installed app" (line 7); the data container keeps its path and the state file is not written by the plugin (8); the new app is on page 2 count 3 (9); installed executable sha256 == packaged (10) |
| 3 forced reinstall (11-17) | the installed Info.plist changed by hand -> "uninstall + install (... Info.plist changed)" (13); the plugin writes hr-state.json into the new install (14); page 2 count 3 (15); installed executable and Info.plist sha256 == packaged (16, 17): the old executable is not what runs |
| 4 data container deleted (18-23) | warm restart OK, "the app's data container is gone" -> fresh install (21), the app runs from its first page, executable sha256 == packaged (23) |
| 5 app uninstalled (24-29) | warm restart OK, "the app is not installed" -> fresh install (27), executable sha256 == packaged (29) |
| 6 (30-33) | the next warm restart updates in place again (32) |
| 7 (34-35) | accessibility note exactly once in `hotReloadRun` and in each of the 5 warm restarts (the setting is only read) |

## Regression on the official .10

| File (line) | Result |
|---|---|
| `unit-tests.txt` (18) | 96/96 (new: `RelaunchTest`, 7) |
| `accept-relaunch.txt` (37) | 28/28 |
| `accept-watch.txt` (21) | 16/16 |
| `accept-kmp.txt` (110; release unchanged 103) | 64/64 |
| `accept-busy.txt` (30) | 11/11 |
| `accept-warm.txt` (110) | 16/16 |
| `accept-composite.txt` (94; release unchanged 34) | 50/50 |

Template runs used the plugin published locally as 0.2.7-dev62c (= 504ef0f); the example app builds the plugin from source.
