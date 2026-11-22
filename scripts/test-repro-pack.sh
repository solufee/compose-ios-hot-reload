#!/bin/bash
# Self-test for scripts/repro-pack.py and the release gate in gen-bundle-manifest.py (Issue #96).
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
RP="python3 $HERE/repro-pack.py"
T=$(mktemp -d "${TMPDIR:-/tmp}/test-repro.XXXXXX"); trap 'rm -rf "$T"' EXIT
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: '$2' vs '$3'"; fail=1; fi; }
differ() { if [ "$2" != "$3" ]; then echo "ok   $1"; else echo "FAIL $1: both '$2'"; fail=1; fi; }
arch() { $RP tar "$1" | zstd -q -19 -T0 | shasum -a 256 | cut -d' ' -f1; }

mkdir -p "$T/a/bin" "$T/a/lib/sub"
printf 'tool\n' > "$T/a/bin/tool"; chmod 755 "$T/a/bin/tool"
printf 'data\n' > "$T/a/lib/sub/data.txt"; chmod 600 "$T/a/lib/sub/data.txt"
ln -s ../lib/sub/data.txt "$T/a/bin/link"
export SOURCE_DATE_EPOCH=1700000000
s1=$(arch "$T/a")

cp -Rp "$T/a" "$T/b"   # same files, other location
touch -t 202001010000 "$T/b/bin/tool" "$T/b/lib/sub/data.txt" "$T/b/lib"; chmod 644 "$T/b/lib/sub/data.txt"; chmod 700 "$T/b/bin/tool"
check "same files, other mtimes, location and non-exec mode bits: same archive" "$s1" "$(arch "$T/b")"
check "content digest of a dir equals that of its archive" "$($RP digest "$T/a")" \
  "$($RP tar "$T/a" | zstd -q -19 -T0 > "$T/a.tar.zst"; $RP digest-archive "$T/a.tar.zst")"
mkdir -p "$T/x"; zstd -dcq "$T/a.tar.zst" | tar -xf - -C "$T/x"
check "archive extracts to the same files" "$($RP digest "$T/a")" "$($RP digest "$T/x")"
check "tar headers: owner 0/0, no names, fixed mtime" "$(zstd -dcq "$T/a.tar.zst" | python3 -c '
import sys, tarfile
t = tarfile.open(fileobj=sys.stdin.buffer, mode="r|")
print(sorted({(m.uid, m.gid, m.uname, m.gname, m.mtime) for m in t}))')" "[(0, 0, '', '', 1700000000)]"

printf 'data2\n' > "$T/b/lib/sub/data.txt"
differ "changed bytes: other content digest" "$($RP digest "$T/a")" "$($RP digest "$T/b")"
cp -Rp "$T/a" "$T/c"; chmod 644 "$T/c/bin/tool"
differ "lost execute bit: other content digest" "$($RP digest "$T/a")" "$($RP digest "$T/c")"
differ "other SOURCE_DATE_EPOCH: other archive" "$s1" "$(SOURCE_DATE_EPOCH=1700000001 arch "$T/a")"
( unset SOURCE_DATE_EPOCH; $RP tar "$T/a" > /dev/null 2>&1 ) && { echo "FAIL tar without SOURCE_DATE_EPOCH must fail"; fail=1; } || echo "ok   tar without SOURCE_DATE_EPOCH fails"

# Release gate: --channel release refuses a packaging manifest without a passing reproducibility record.
cat > "$T/pk.json" <<'EOF'
{"schema": 3, "toolchainVersion": "0.0.0-test", "generatedAt": "2023-11-14T22:13:20+00:00", "kaldoIossimSha256": "k",
 "layout": {"kotlinDist": "kotlin-native", "composePluginJar": "compose-plugin/p.jar", "composeKlibDir": "compose-klibs",
            "kaldoHome": "kaldo", "kaldoIossim": "kaldo/lib/libKaldo.iossim.dylib"},
 "components": [{"id": "c", "file": "c.tar.zst", "extractDir": "c", "rawBytes": 1, "bytes": 1, "sha256": "s", "channel": "release"}]}
EOF
cat > "$T/id.json" <<'EOF'
{"interfaceVersion": 8, "kotlin": {"version": "v", "baseCommit": "b", "patch": "p", "patchSha256": "s"},
 "compose": {"version": "v", "baseCommit": "b", "patch": "p", "patchSha256": "s"},
 "llvm": {"baseCommit": "b", "patch": "p", "patchSha256": "s", "kaldoIossimSha256": "k"}, "host": {}, "runtime": {}}
EOF
GB="python3 $HERE/gen-bundle-manifest.py --identity $T/id.json --channel release"
$GB --packaging "$T/pk.json" --out "$T/tc.json" > /dev/null 2>&1 && { echo "FAIL release manifest without gate record was written"; fail=1; } \
  || echo "ok   release manifest without a reproducibility record is refused"
python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); m["reproducibility"]={"result":"identical"}; json.dump(m,open(sys.argv[1],"w"))' "$T/pk.json"
$GB --packaging "$T/pk.json" --out "$T/tc.json" > /dev/null && cp "$T/tc.json" "$T/tc1.json" && $GB --packaging "$T/pk.json" --out "$T/tc.json" > /dev/null \
  && check "with a passing record: written, same bytes twice (generatedAt from the packaging manifest)" \
     "$(shasum -a 256 < "$T/tc1.json")" "$(shasum -a 256 < "$T/tc.json")" || { echo "FAIL release manifest with gate record"; fail=1; }
[ $fail = 0 ] && echo "test-repro-pack: all passed" || { echo "test-repro-pack: FAILED"; exit 1; }
