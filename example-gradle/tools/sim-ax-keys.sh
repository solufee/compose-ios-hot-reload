#!/bin/bash
# Save and restore a simulator's accessibility switch around tests that use `axe describe-ui` (Issue #60).
#
# `axe describe-ui` turns on ApplicationAccessibilityEnabled and AccessibilityEnabled in com.apple.Accessibility; with
# them on, UIKit loads the accessibility bundles at every app start (~1.8 s before didFinishLaunching). `axe tap` alone
# does not change them. Call `save` before the first describe-ui and `restore` when the test ends (also on failure).
#
# Usage:
#   sim-ax-keys.sh save <udid>      read both keys and store them in the state file (absent = not set)
#   sim-ax-keys.sh restore <udid>   write the stored values back (-bool YES/NO; absent -> NO, never deleted: with the
#                                   key absent describe-ui turns it on again), read back, compare, remove the state file
#   sim-ax-keys.sh show <udid>      print the current values
# State file: $SIM_AX_KEYS_DIR (default ${TMPDIR:-/tmp}) / sim-ax-keys-<udid>.state. If it already exists, `save` keeps
# it (an earlier run ended before `restore`, so the file holds the real original); `save --force` overwrites it.
# Output: one line, "ax saved: App=<v> AX=<v>" / "ax restored: App=<v> AX=<v> (before describe-ui: App=<v> AX=<v>)",
# v = 1 | 0 | absent. Exit 0 on success; restore exits 1 if the read-back differs from the stored values (absent counts
# as 0), 2 on usage errors or a missing state file. Only reads/writes these two keys; needs a booted simulator.
set -u
KEYS="ApplicationAccessibilityEnabled AccessibilityEnabled"
usage() { sed -n '9,13p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
force=0; [ "${2:-}" = "--force" ] && { force=1; set -- "$1" "${3:-}"; }
cmd=${1:-}; udid=${2:-}
[ -n "$cmd" ] && [ -n "$udid" ] || usage
state="${SIM_AX_KEYS_DIR:-${TMPDIR:-/tmp}}"; state="${state%/}/sim-ax-keys-$udid.state"
read_key() {  # prints 1 | 0 | absent
  local v; v=$(xcrun simctl spawn "$udid" defaults read com.apple.Accessibility "$1" 2>/dev/null) || { echo absent; return; }
  case "$v" in 1|YES|true) echo 1;; *) echo 0;; esac
}
fmt() { echo "App=$1 AX=$2"; }
case "$cmd" in
  show)
    echo "ax now: $(fmt "$(read_key ApplicationAccessibilityEnabled)" "$(read_key AccessibilityEnabled)")";;
  save)
    if [ -f "$state" ] && [ "$force" = 0 ]; then
      . "$state"; echo "ax saved: $(fmt "$App" "$AX") (kept the state file of an earlier run that did not restore: $state)"; exit 0
    fi
    App=$(read_key ApplicationAccessibilityEnabled); AX=$(read_key AccessibilityEnabled)
    printf 'App=%s\nAX=%s\n' "$App" "$AX" > "$state.tmp" && mv "$state.tmp" "$state"
    echo "ax saved: $(fmt "$App" "$AX")";;
  restore)
    [ -f "$state" ] || { echo "ax restore: no state file $state (run save first)" >&2; exit 2; }
    . "$state"
    b() { [ "$1" = 1 ] && echo YES || echo NO; }
    xcrun simctl spawn "$udid" defaults write com.apple.Accessibility ApplicationAccessibilityEnabled -bool "$(b "$App")"
    xcrun simctl spawn "$udid" defaults write com.apple.Accessibility AccessibilityEnabled -bool "$(b "$AX")"
    nApp=$(read_key ApplicationAccessibilityEnabled); nAX=$(read_key AccessibilityEnabled)
    wApp=$([ "$App" = 1 ] && echo 1 || echo 0); wAX=$([ "$AX" = 1 ] && echo 1 || echo 0)
    line="ax restored: $(fmt "$nApp" "$nAX") (before describe-ui: $(fmt "$App" "$AX"))"
    if [ "$nApp" = "$wApp" ] && [ "$nAX" = "$wAX" ]; then rm -f "$state"; echo "$line"; else echo "$line MISMATCH"; exit 1; fi;;
  *) usage;;
esac
