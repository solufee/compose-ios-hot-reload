#!/bin/bash
# Toolchain .11 release zero-change check (Issue #60): the example built in release (-opt, no split, no hot reload) with
# toolchain A and toolchain B must give the same GOT-order-independent __text digest (scripts/release-text-digest.py),
# the same defined symbols (normalized: per-build hash prefixes and LLVM .N suffixes) and the same section sizes. Exit 0 = PASS. Usage: accept-release-11.sh <env-A.sh> <env-B.sh> <out-dir>
set -u
A=$1; B=$2; O=$(mkdir -p "$3" && cd "$3" && pwd); HERE=$(cd "$(dirname "$0")" && pwd)
build() {  # $1 env file, $2 tag
  ( . "$1"; set -e; D=$O/$2; rm -rf "$D"; mkdir -p "$D"
    LIBS=(); while IFS= read -r l; do
      case "$l" in ''|'#'*|*/klib/common/*|*/klib/platform/*) continue;; esac; LIBS+=(-l "$l")
    done < "$KLIBLIST"
    KC="$KOTLIN_DIST/bin/kotlinc-native"; COMMON=(-target ios_simulator_arm64 -opt -Xplugin="$COMPOSE_PLUGIN_JAR" -opt-in=kotlinx.cinterop.ExperimentalForeignApi)
    "$KC" "${COMMON[@]}" -p library -o "$D/ui" $(find "$HERE/ui/src/main/kotlin" -name '*.kt') "${LIBS[@]}" > "$D/ui.out" 2>&1
    "$KC" "${COMMON[@]}" -p program -o "$D/rel" $(find "$HERE/app/src/main/kotlin" -name '*.kt') -l "$D/ui.klib" "${LIBS[@]}" \
      -linker-option -framework -linker-option ImageIO -linker-option -Wl,-no_warn_duplicate_libraries \
      -linker-option -force_load -linker-option "$UIKIT_UTILS" > "$D/app.out" 2>&1
    nm -U -j "$D/rel.kexe" | grep -v '^$' | sort > "$D/symbols.txt"
    size -m "$D/rel.kexe" | grep -E 'Segment|Section' > "$D/sizes.txt"
    echo "$2: kexe $(stat -f %z "$D/rel.kexe") bytes, symbols $(wc -l < "$D/symbols.txt" | tr -d ' '), __text digest $(python3 -I "$HERE/../scripts/release-text-digest.py" "$D/rel.kexe" | cut -c1-16)" )
}
if [ "${REUSE_A:-0}" = 1 ] && [ -f "$O/A/rel.kexe" ]; then echo "A: reused $(stat -f %z "$O/A/rel.kexe") bytes"; else build "$A" A || { echo "build A failed"; exit 1; }; fi
build "$B" B || { echo "build B failed"; exit 1; }
DA=$(python3 -I "$HERE/../scripts/release-text-digest.py" "$O/A/rel.kexe" | cut -d' ' -f1)
DB=$(python3 -I "$HERE/../scripts/release-text-digest.py" "$O/B/rel.kexe" | cut -d' ' -f1)
[ -n "$DA" ] && [ -n "$DB" ] || echo "__text digest: could not be computed (release-text-digest.py failed)"
[ -n "$DA" ] && [ "$DA" = "$DB" ] && echo "__text digest: identical (${DA:0:16})" || echo "__text digest: DIFFERS (${DA:0:16} vs ${DB:0:16})"
# Defined symbols are compared after normalizing what differs between any two builds of the same sources: the
# per-build hash prefix of compiler-generated helper names (__<32 hex>...) and LLVM's local '.N' suffixes. Raw name
# differences are printed for information only; the verdict uses the normalized lists.
norm_syms() { sed -E 's/^__[0-9a-f]{32}/__<hash>/; s/\.[0-9]+(\.[0-9]+)?$/.N/' "$1" | sort; }
SYMS_OK=0; cmp -s <(norm_syms "$O/A/symbols.txt") <(norm_syms "$O/B/symbols.txt") && SYMS_OK=1
echo "defined symbols: A $(wc -l < "$O/A/symbols.txt" | tr -d ' '), B $(wc -l < "$O/B/symbols.txt" | tr -d ' '); $([ $SYMS_OK = 1 ] && echo identical || echo DIFFER) after normalizing the per-build hash prefix and LLVM local .N suffixes"
RAW=$(diff "$O/A/symbols.txt" "$O/B/symbols.txt" | grep -c '^[<>]')
[ "$RAW" = 0 ] || echo "  (info) raw symbol names: $RAW lines differ, only in those per-build parts$([ $SYMS_OK = 1 ] || echo ' AND in real names, see below')"
[ $SYMS_OK = 1 ] || diff <(norm_syms "$O/A/symbols.txt") <(norm_syms "$O/B/symbols.txt") | head -10
cmp -s "$O/A/sizes.txt" "$O/B/sizes.txt" && echo "section sizes: identical" || { echo "section sizes: DIFFER"; diff "$O/A/sizes.txt" "$O/B/sizes.txt" | head -10; }
for t in A B; do echo "$t: nm hotReloadFrameCommitted=$(nm "$O/$t/rel.kexe" | grep -c hotReloadFrameCommitted) HotReload_isEnabled=$(nm "$O/$t/rel.kexe" | grep -c HotReload_isEnabled) utf16 marker=$(python3 -c "import sys;print(open(sys.argv[1],'rb').read().count('HR READY first-frame'.encode('utf-16-le')))" "$O/$t/rel.kexe")"; done
if [ -n "$DA" ] && [ "$DA" = "$DB" ] && [ $SYMS_OK = 1 ] && cmp -s "$O/A/sizes.txt" "$O/B/sizes.txt"; then
  echo "RESULT: PASS (same __text digest, section sizes and normalized symbols)"; exit 0
fi
echo "RESULT: FAIL"; exit 1
