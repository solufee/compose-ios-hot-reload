#!/bin/bash
# Profile wrapper: exactly the reload-a.sh kotlinc invocation, no send.
# Usage: profile-compile.sh <tag> [extra kotlinc args...]
set -e
KOTLIN_DIST=${KOTLIN_DIST:?KOTLIN_DIST is required (see README)}
cd "$(dirname "$0")"
TAG=${1:-p0}
shift || true
KC=$KOTLIN_DIST/bin/kotlinc-native
PLUGIN=${COMPOSE_PLUGIN_JAR:?COMPOSE_PLUGIN_JAR is required (see README)}
KLIBLIST=${KLIBLIST:-${KLIBLIST:-$PWD/klibs.txt}}
AUTO_CACHE=${AUTO_CACHE:-$PWD/auto-cache}
IC_DIR=${IC_DIR:-$PWD/ic}
ARGS=()
while IFS= read -r k; do
  case "$k" in
    */.gradle/caches/*) ARGS+=("-l" "$k") ;;
  esac
done < "$KLIBLIST"

/usr/bin/time -p "$KC" -target ios_simulator_arm64 -p program -g \
  -Xcompilation-scheme=split \
  -Xenable-incremental-compilation -Xic-cache-dir="$IC_DIR" \
  -Xbinary=perFileCacheForStdlib=false \
  -Xauto-cache-from="${GRADLE_CACHES:-$(cd ~/.gradle/caches && pwd)}" \
  -Xauto-cache-dir="$AUTO_CACHE" \
  -Xplugin="$PLUGIN" \
  -opt-in=kotlinx.cinterop.ExperimentalForeignApi \
  -linker-option=-framework -linker-option=ImageIO \
  -linker-option=-Wl,-no_warn_duplicate_libraries \
  -o "$PWD/ComposeHR-patch.kexe" "${ARGS[@]}" "$PWD/main.kt" "$@" 2>&1 | tee "profile-$TAG.log"
