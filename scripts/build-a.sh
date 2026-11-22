#!/bin/bash
# Route A build: compile Compose to a split host, merge every cache payload into
# one guest object with ld -r, then re-link the host with that single payload.
set -e
KOTLIN_DIST=${KOTLIN_DIST:?KOTLIN_DIST is required (see README)}
export JAVA_OPTS="-Xmx16g"
KC=$KOTLIN_DIST/bin/kotlinc-native
PLUGIN=${COMPOSE_PLUGIN_JAR:?COMPOSE_PLUGIN_JAR is required (see README)}
KLIBLIST=${KLIBLIST:-${KLIBLIST:-$PWD/klibs.txt}}
AUTO_CACHE=${AUTO_CACHE:-$PWD/auto-cache}
AC_FROM=${AC_FROM:-~/.gradle/caches}
IC_DIR=${IC_DIR:-$PWD/ic}
mkdir -p "$AUTO_CACHE" "$IC_DIR"
ARGS=()
while IFS= read -r k; do
  case "$k" in
    */.gradle/caches/*) ARGS+=("-l" "$k") ;;
  esac
done < "$KLIBLIST"

COMMON=(-target ios_simulator_arm64 -p program -g
  -Xcompilation-scheme=split
  -Xenable-incremental-compilation -Xic-cache-dir="$IC_DIR"
  -Xbinary=perFileCacheForStdlib=false
  -Xauto-cache-from="$AC_FROM"
  -Xauto-cache-dir="$AUTO_CACHE"
  -Xplugin="$PLUGIN"
  -opt-in=kotlinx.cinterop.ExperimentalForeignApi
  -linker-option=-framework -linker-option=ImageIO
  -linker-option=-force_load -linker-option="$PWD/libCMPUIKitObjcUtils.a"
  -linker-option=-Wl,-no_warn_duplicate_libraries)

echo "== klib preflight =="
python3 "$PWD/verify-klibs.py" "$KLIBLIST" "$PWD/klib-signature.txt"

echo "== pass 1: base host with per-cache payloads =="
"$KC" "${COMMON[@]}" -o "$PWD/ComposeHR-base.kexe" "${ARGS[@]}" "$PWD/main.kt"

echo "== merge payloads =="
# The one merge-payloads.py (the plugin ships the same file); this directory has no copy of its own (Issue #91).
python3 "$(cd "$(dirname "$0")" && pwd)/../example-gradle/tools/merge-payloads.py" "$PWD/ComposeHR-base.kexe" "$PWD/merged-bootstrap.o"

echo "== embed merged guest object =="
python3 "$PWD/embed-merged.py" "$PWD/ComposeHR-base.kexe" "$PWD/merged-bootstrap.o" "$PWD/ComposeHR.kexe"
echo "== done =="
