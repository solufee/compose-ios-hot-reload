# Plugin 0.2.7 on toolchain 2.5.255-hotreload.11 - evidence (#62)

Official toolchain .11 (manifest sha256 1a669ba9...; kotlin 1ccbcca1, compose 8f9229c0, llvm 81fa2129;
`runtime.readyMarker` = "HR READY first-frame"), installed with `scripts/fetch-toolchain.py` from the local mirror
(`fetch-11.txt`: 8 components and 27 public klibs, sha256 checked). 0.2.7 = #62 items 1-2 (in-place update,
event-driven readiness, accessibility note; PR #67) + item 3 (first-frame ready marker, INTERFACE §6.1).

## Item 3: first-frame ready marker (relaunch step of the warm restart, `../relaunch-62/`)

Same plugin and toolchain; "before" turns the marker off for the app (`SIMCTL_CHILD_KONAN_HR_READY_MARKER=0`, the old
rule), "after" leaves it on. 3 library edits (ui1-3, lines 3-5) + 3 app edits (app1-3, lines 6-8); `ready_by` on every
run line comes from that run's `WARM OK … ready=` line; medians from line 12, `ms_relaunch` line 30, `ready_by` line 31.

| Project | Before | After | Ready by (per run) |
|---|---|---|---|
| wizard template + core | 6236 ms (`relaunch-tc11-before-template.txt` 30) | **5250 ms** (`relaunch-tc11-after-template.txt` 30) | before rule x6, after marker x6 (lines 3-8, 31) |
| example app (sets `readyMarker = "RENDER_APP"`, which wins) | 4874 ms (`relaunch-tc11-before-example.txt` 30) | 4882 ms (`relaunch-tc11-after-example.txt` 30) | user-marker x6 both |
| example app without its own readyMarker (warm restart state page/count) | 3168 ms at 4166da6 (`relaunch-tc11-example-nouser-4166da6.txt` 30): too early, see below | **4954 ms** at 7b06c92 (`relaunch-tc11-example-nouser.txt` 30) | state x6 -> marker x6 (31) |

The example without its own readyMarker showed that the state line (`HR STATE restore page=2 count=3`, printed when
Kotlin main starts) made the app "ready" 78 ms after Kotlin main (line 26), before anything was drawn. INTERFACE §6.1
gives the plugin the marker or the 3 s rule, with page/count checked at that moment; since 7b06c92 a state line alone no
longer counts when the toolchain has the marker (older toolchains keep the 0.2.6 behaviour; `ReadyStateTest`). That
run then takes the marker 6/6 (4954 ms, about what the app's own `RENDER_APP` marker gives: 4882 ms), and
`accept-relaunch-no-user-marker.txt` is 28/28 with `ready=marker` on all 5 warm restarts (page/count kept on both
install paths).

`kotlin_main_to_ready` (line 26) on the template: 3155 -> 1919 ms. On the .11 dev view the same change measured
6219 -> 4930 ms (`relaunch-marker-dev11-template.txt`). For comparison, 0.2.6 on .10: template 6767 ms, example 5681 ms
(`relaunch-before-*.txt`).

## Regression on the official .11

| File (line) | Result |
|---|---|
| `unit-tests.txt` (19) | 103/103 at 7b06c92 (new: `ReadyStateTest` 7, `RelaunchTest` 7) |
| `accept-relaunch.txt` (37) | 28/28: in-place update, forced reinstall, data container deleted, app uninstalled, accessibility note (example's own readyMarker) |
| `accept-relaunch-no-user-marker.txt` (37) | 28/28 at 7b06c92, without the example's readyMarker: `ready=marker` x5 |
| `accept-watch.txt` (23) | 17/17 |
| `accept-kmp.txt` (112; release unchanged 104; "ready: first frame (toolchain marker)" 30) | 65/65 |
| `accept-busy.txt` (32) | 12/12 |
| `accept-warm.txt` (112) | 17/17 |
| `accept-composite.txt` (96; release unchanged 35) | 51/51 |

Each accept script ends with its simulator accessibility check (#66, `ax restored`).

The accept scripts other than accept-relaunch ran on 0.2.7-rc (4166da6, published locally). 7b06c92 changes only the
case "toolchain marker + warm restart state + no readyMarker of the user's"; none of those runs has it (the template
saves no state: `page=- count=-`), so they are not repeated.
