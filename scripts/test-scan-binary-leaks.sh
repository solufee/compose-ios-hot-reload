#!/bin/bash
# Negative and positive tests for scan-binary-leaks.py (run before packing a toolchain).
#   scripts/test-scan-binary-leaks.sh
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
SCAN=$HERE/scan-binary-leaks.py
T=$(mktemp -d "${TMPDIR:-/tmp}/scan-test.XXXXXX")
trap 'rm -rf "$T"' EXIT
fail=0
expect() { # expect <want-exit> <name> <dir>
  python3 "$SCAN" "$3" > "$T/out" 2>&1; local rc=$?
  if [ "$rc" = "$1" ]; then echo "ok   $2 (exit $rc)"; else echo "FAIL $2: exit $rc, want $1"; sed 's/^/     /' "$T/out"; fail=1; fi
}
HP="/Us""ers/someone/project/src/a.kt"

mkdir -p "$T/leak" && printf 'abc\0%s\0def' "$HP" > "$T/leak/x.bin"
expect 1 "home path in a binary file" "$T/leak"

SYM="__ZN4llvm16IntervalPressure10open""BottomE"   # a real LLVM symbol; must not count as an internal name
mkdir -p "$T/llvm" && printf '\0%s\0' "$SYM" > "$T/llvm/x.dylib"
expect 0 "an ordinary LLVM symbol is not flagged" "$T/llvm"

NAME="Open""Bot"   # the internal system name (from the local deny file), as a word
mkdir -p "$T/name" && printf '\0%s/Shared\0' "$NAME" > "$T/name/x.bin"
expect 1 "internal name from the deny file is flagged" "$T/name"

mkdir -p "$T/zip" && (cd "$T" && printf '%s\n' "$HP" > inner.txt && python3 - <<'PY'
import zipfile
with zipfile.ZipFile("zip/lib.klib", "w", zipfile.ZIP_DEFLATED) as z:
    z.write("inner.txt", "default/ir/strings.knt")
PY
)
expect 1 "home path inside a zip member (compressed)" "$T/zip"

SK="$T/skiko/org.jetbrains.skiko/skiko-iossimulatorarm64/0.157.0-alpha01"
mkdir -p "$SK" && (cd "$T" && printf '%s\n' "/Us""ers/admin/buildAgent/work/381d14fe560aa354/skiko/src/a.kt" > ok.txt \
  && printf '%s\n' "$HP" > bad.txt && python3 - "$SK" <<'PY'
import sys, zipfile
d = sys.argv[1]
with zipfile.ZipFile(d + "/skiko-iosSimulatorArm64Main-0.157.0-alpha01.klib", "w") as z:
    z.write("ok.txt", "default/ir/strings.knt")
PY
)
expect 0 "skiko klib with only the allowed upstream build path" "$T/skiko"
(cd "$T" && python3 - "$SK" <<'PY'
import sys, zipfile
d = sys.argv[1]
with zipfile.ZipFile(d + "/skiko-iosSimulatorArm64Main-0.157.0-alpha01.klib", "w") as z:
    z.write("ok.txt", "default/ir/strings.knt")
    z.write("bad.txt", "default/ir/other.knt")
PY
)
expect 1 "skiko klib with any other home path" "$T/skiko"

python3 "$SCAN" --help > "$T/out" 2>&1; rc=$?
if [ "$rc" = 2 ] && grep -q usage "$T/out"; then echo "ok   --help prints usage (exit 2)"; else echo "FAIL --help: exit $rc"; fail=1; fi

[ "$fail" = 0 ] && echo "all scan tests passed"
exit "$fail"
