#!/bin/bash
# Recompile the user module and send its newest incremental-cache object to the
# running Compose host. Usage: reload-a.sh <output-name>
set -e
KOTLIN_DIST=${KOTLIN_DIST:?KOTLIN_DIST is required (see README)}
OUT=${1:-ComposeHR-patch.kexe}
KC=$KOTLIN_DIST/bin/kotlinc-native
PLUGIN=${COMPOSE_PLUGIN_JAR:?COMPOSE_PLUGIN_JAR is required (see README)}
KLIBLIST=${KLIBLIST:-$PWD/klibs.txt}
AUTO_CACHE=${AUTO_CACHE:-$PWD/auto-cache}
IC_DIR=${IC_DIR:-$PWD/ic}
ARGS=()
while IFS= read -r k; do
  case "$k" in
    */.gradle/caches/*) ARGS+=("-l" "$k") ;;
  esac
done < "$KLIBLIST"
STAMP=$(mktemp)
touch "$STAMP"
sleep 1
"$KC" -target ios_simulator_arm64 -p program -g \
  -Xcompilation-scheme=split \
  -Xenable-incremental-compilation -Xic-cache-dir="$IC_DIR" \
  -Xbinary=perFileCacheForStdlib=false \
  -Xauto-cache-from="${GRADLE_CACHES:-$(cd ~/.gradle/caches && pwd)}" \
  -Xauto-cache-dir="$AUTO_CACHE" \
  -Xplugin="$PLUGIN" \
  -opt-in=kotlinx.cinterop.ExperimentalForeignApi \
  -linker-option=-framework -linker-option=ImageIO \
  -linker-option=-Wl,-no_warn_duplicate_libraries \
  -o "$PWD/$OUT" "${ARGS[@]}" "$PWD/main.kt"
NEW=$(find "$IC_DIR" -name "*.a" -newer "$STAMP" | grep -v -- "-per-file-cache" | head -1)
if [ -z "$NEW" ]; then
  NEW=$(find "$IC_DIR" -name "*.a" -newer "$STAMP" | head -1)
fi
echo "user archive: $NEW"
rm -rf patch-current && mkdir patch-current
(cd patch-current && ar x "$NEW")
OBJ=$(ls patch-current/*.o | head -1)
# The externalizer skips graphs whose name contains "-cache.a"; send the object
# under a neutral name so the reload path externalizes it like the bootstrap.
cp "$OBJ" patch-current/patch.o
echo "object: $OBJ -> patch.o"
python3 "${RELOAD_PY:-$(dirname "$0")/../demo/reload.py}" "$PWD/patch-current/patch.o"
