#!/bin/bash
# Safety tests for export-public.sh: options never become an output directory,
# and only directories the script created itself are ever cleaned.
#   scripts/test-export-public.sh      (exports the committed HEAD twice)
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
EXP=$HERE/export-public.sh
T=$(mktemp -d "${TMPDIR:-/tmp}/export-test.XXXXXX")
trap 'rm -rf "$T"' EXIT
fail=0
check() { # check <name> <want-exit> <got-exit> [extra condition result 0/1]
  if [ "$3" = "$2" ] && [ "${4:-0}" = 0 ]; then echo "ok   $1 (exit $3)"; else echo "FAIL $1: exit $3, want $2 (extra=${4:-0})"; fail=1; fi
}

(cd "$T" && bash "$EXP" --help) > "$T/o" 2>&1; rc=$?
grep -q usage "$T/o"; check "--help prints usage, touches nothing" 0 $rc $?
[ ! -e "$T/--help" ]; check "  no directory named --help" 0 0 $?

(cd "$T" && bash "$EXP" -x) > "$T/o" 2>&1; check "unknown option is not an output dir" 2 $?

mkdir -p "$T/foreign" && echo keep > "$T/foreign/important.txt"
bash "$EXP" "$T/foreign" > "$T/o" 2>&1; rc=$?
[ -f "$T/foreign/important.txt" ]; check "existing dir without marker is refused and kept" 2 $rc $?

echo keep > "$T/afile"
bash "$EXP" "$T/afile" > "$T/o" 2>&1; rc=$?
[ -f "$T/afile" ]; check "existing file is refused and kept" 2 $rc $?

bash "$EXP" "$T/new" --commit > "$T/o" 2>&1; rc=$?
[ ! -e "$T/new" ]; check "--commit without a value is refused before any write" 2 $rc $?

bash "$EXP" "$HERE/.." > "$T/o" 2>&1; check "the repository itself is refused" 2 $?

bash "$EXP" "$T/out" > "$T/o" 2>&1; rc=$?
[ -f "$T/out/.export-public" ]; check "new dir is created and exported" 0 $rc $?

echo stale > "$T/out/stale.txt"
bash "$EXP" "$T/out" > "$T/o" 2>&1; rc=$?
[ -f "$T/out/.export-public" ] && [ ! -e "$T/out/stale.txt" ]; check "re-export into its own marked dir is allowed" 0 $rc $?

[ "$fail" = 0 ] && echo "all export safety tests passed"
exit "$fail"
