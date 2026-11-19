#!/bin/bash
# Hot reload compile via the persistent compiler daemon (same args as reload-a.sh).
# Usage: compile-daemon.sh [extra kotlinc args...]
set -e
SOCK=${HRC_SOCKET:-$PWD/.hr/hr-compile.sock}
PLUGIN=${COMPOSE_PLUGIN_JAR:?COMPOSE_PLUGIN_JAR is required (see README)}
KLIBLIST=${KLIBLIST:-$PWD/klibs.txt}
AUTO_CACHE=${AUTO_CACHE:-$PWD/auto-cache}
IC_DIR=${IC_DIR:-$PWD/ic}
ARGS=(-target ios_simulator_arm64 -p program -g
  -Xcompilation-scheme=split
  -Xenable-incremental-compilation -Xic-cache-dir="$IC_DIR"
  -Xbinary=perFileCacheForStdlib=false
  -Xauto-cache-from="${GRADLE_CACHES:-$(cd ~/.gradle/caches && pwd)}"
  -Xauto-cache-dir="$AUTO_CACHE"
  -Xplugin="$PLUGIN"
  -opt-in=kotlinx.cinterop.ExperimentalForeignApi
  -linker-option=-framework -linker-option=ImageIO
  -linker-option=-Wl,-no_warn_duplicate_libraries
  -Xdump-built-caches-to=/tmp/hr-last-caches.txt
  -o "$PWD/ComposeHR-patch.kexe")
while IFS= read -r k; do
  case "$k" in
    */.gradle/caches/*) ARGS+=("-l" "$k") ;;
  esac
done < "$KLIBLIST"
ARGS+=("$PWD/main.kt")
python3 hrc.py "$SOCK" "${ARGS[@]}" "$@"
