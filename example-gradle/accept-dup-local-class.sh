#!/bin/bash
# Issue #91 (toolchain part) regression: two files of ONE module, each with a function named `module` that holds a lambda
# (the shape Koin's compiler plugin generates for two @Module classes of one package). Both lambdas are local classes
# named `<package>.module$1`. The hot reload compile (split scheme, per-file caches) must give their members different
# symbol names, decided by the file only, so merge-payloads' ld -r links them; a second compile, also with one more file
# in the module, must give the same names (the packaged app and a later patch must agree).
# Usage: KOTLIN_DIST=<dist> accept-dup-local-class.sh <merge-payloads.py> <work dir>     (exit 0 = PASS)
set -u
DIST=${KOTLIN_DIST:?}; MERGE=$1; W=$2; KC=$DIST/bin/kotlinc-native
[ -d "$DIST/../llvm-tools" ] && export KONAN_LLVM_HOME=${KONAN_LLVM_HOME:-$(cd "$DIST/../llvm-tools" && pwd)}
rm -rf "$W"; mkdir -p "$W/src/lam" "$W/app"
printf 'package lam\n\nclass LamA\n\nfun LamA.module(): () -> String = { "a1" }\n' > "$W/src/lam/LamA.kt"
printf 'package lam\n\nclass LamB\n\nfun LamB.module(): () -> String = { "b1" }\n' > "$W/src/lam/LamB.kt"
printf 'package lam\n\nfun third(): () -> String = { "c1" }\n' > "$W/src/lam/LamC.kt"
printf 'import lam.*\n\nfun main() { println("LAM " + LamA().module()() + "/" + LamB().module()()) }\n' > "$W/app/main.kt"

build() {  # <tag> <files...>: klib + split compile into $W/<tag>; prints the module$1 symbols of the per-file caches
  local tag=$1; shift
  mkdir -p "$W/$tag/ic" "$W/$tag/auto"
  "$KC" -target ios_simulator_arm64 -p library -module-name lamlib "$@" -o "$W/$tag/lamlib" > "$W/$tag/klib.log" 2>&1 \
    || { echo "FAIL setup: klib ($tag)"; tail -3 "$W/$tag/klib.log"; exit 2; }
  "$KC" -target ios_simulator_arm64 -p program -g -Xcompilation-scheme=split -Xenable-incremental-compilation \
    -Xic-cache-dir="$W/$tag/ic" -Xbinary=perFileCacheForStdlib=false -Xauto-cache-dir="$W/$tag/auto" \
    -l "$W/$tag/lamlib.klib" -Xbinary=splitPayloadList="$W/$tag/payloads.txt" -o "$W/$tag/lam.kexe" "$W/app/main.kt" \
    > "$W/$tag/compile.log" 2>&1 || { echo "FAIL setup: compile ($tag)"; tail -5 "$W/$tag/compile.log"; exit 2; }
  find "$W/$tag/ic" -name "liblam*.a" | while read -r a; do nm -g "$a" | awk '/ T / && /module\$1/ {print $3}'; done | sort
}

build one "$W/src/lam/LamA.kt" "$W/src/lam/LamB.kt" > "$W/names-1.txt"
build two "$W/src/lam/LamC.kt" "$W/src/lam/LamA.kt" "$W/src/lam/LamB.kt" > "$W/names-2.txt"
echo "module\$1 symbols of the per-file caches (first compile):"
sed 's/^/  /' "$W/names-1.txt"
fail=0
total=$(grep -c . "$W/names-1.txt"); uniq=$(sort -u "$W/names-1.txt" | grep -c .)
a=$(grep -c 'LamA.kt:' "$W/names-1.txt"); b=$(grep -c 'LamB.kt:' "$W/names-1.txt")
if [ "$total" -gt 0 ] && [ "$a" -gt 0 ] && [ "$b" -gt 0 ] && [ "$a" = "$b" ] && [ $((a + b)) = "$total" ]; then
  echo "PASS names carry their file: LamA.kt $a, LamB.kt $b"
else echo "FAIL names do not carry their file (total=$total LamA=$a LamB=$b)"; fail=1; fi
if cmp -s "$W/names-1.txt" "$W/names-2.txt"; then echo "PASS same names in a second compile with one more file in the module"
else echo "FAIL names changed between compiles:"; diff "$W/names-1.txt" "$W/names-2.txt" | head -6; fail=1; fi
KALDO_PAYLOAD_LIST="$W/one/payloads.txt" KALDO_HOT_PACKAGES=lam KALDO_HOT_SYMBOLS_OUT="$W/hot-symbols.txt" \
  python3 "$MERGE" "$W/one/lam.kexe" "$W/merged.o" > "$W/merge.log" 2>&1
rc=$?
grep -E "duplicate definitions|duplicate symbol|two files of one library" "$W/merge.log" | head -4 | sed 's/^/  /'
if [ $rc -eq 0 ]; then echo "PASS merge-payloads rc=0 (the two lambdas link side by side)"
else echo "FAIL merge-payloads rc=$rc"; fail=1; fi
[ $fail = 0 ] && echo "summary: PASS" || echo "summary: FAIL"
exit $fail
