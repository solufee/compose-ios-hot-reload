#!/bin/bash
# #77 regression: two modules contain a file with the same relative path and the same private top-level function (what a
# compiler plugin that generates one fixed file per module produces; here made with -Xklib-relative-path-base, no plugin).
# The hot reload compile (split scheme, per-file caches) must give the two private functions and the two per-file caches'
# own constructors different symbol names, so merge-payloads' ld -r does not stop on duplicate symbols.
# Usage: KOTLIN_DIST=<dist> accept-dup-private.sh <merge-payloads.py> <work dir>     (exit 0 = PASS)
set -u
DIST=${KOTLIN_DIST:?}; MERGE=$1; W=$2; KC=$DIST/bin/kotlinc-native
rm -rf "$W"; mkdir -p "$W/a/same" "$W/b/same" "$W/app" "$W/auto" "$W/ic"
for m in a b; do
  printf 'package same\n\nprivate fun dupHelper(): String = "helper-%s"\n\nfun fromModule%s(): String = dupHelper()\n' $m $m > "$W/$m/same/Same.kt"
  "$KC" -target ios_simulator_arm64 -p library -Xklib-relative-path-base="$W/$m" -module-name dup$m "$W/$m/same/Same.kt" \
    -o "$W/dup$m" > "$W/klib-$m.log" 2>&1 || { echo "FAIL setup: klib $m"; tail -3 "$W/klib-$m.log"; exit 2; }
done
printf 'import same.*\n\nfun main() { println("DUP " + fromModulea() + " " + fromModuleb()) }\n' > "$W/app/main.kt"
"$KC" -target ios_simulator_arm64 -p program -g -Xcompilation-scheme=split -Xenable-incremental-compilation \
  -Xic-cache-dir="$W/ic" -Xbinary=perFileCacheForStdlib=false -Xauto-cache-dir="$W/auto" \
  -l "$W/dupa.klib" -l "$W/dupb.klib" -Xbinary=splitPayloadList="$W/payloads.txt" -o "$W/dup.kexe" "$W/app/main.kt" \
  > "$W/compile.log" 2>&1 || { echo "FAIL setup: compile"; tail -5 "$W/compile.log"; exit 2; }
echo "private symbols in the per-file caches:"
find "$W/ic" -name "libsame*.a" | while read -r a; do nm -g "$a" | awk '/ T / && /dupHelper|Konan_init/ {print "  " $3}'; done | sort -u
KALDO_PAYLOAD_LIST="$W/payloads.txt" KALDO_HOT_PACKAGES=same KALDO_HOT_SYMBOLS_OUT="$W/hot-symbols.txt" \
  python3 "$MERGE" "$W/dup.kexe" "$W/merged.o" > "$W/merge.log" 2>&1
rc=$?
if [ $rc -eq 0 ]; then echo "PASS merge-payloads rc=0 (no duplicate symbols)"; exit 0; fi
grep -E "duplicate symbol|ld: [0-9]+ duplicate" "$W/merge.log" | head -4
echo "FAIL merge-payloads rc=$rc"; exit 1
