#!/bin/bash
# Hot reload through the persistent compiler daemon.
# Usage: reload-c.sh [output-name]
set -e
OUT=${1:-ComposeHR-patch.kexe}
IC_DIR=${IC_DIR:-$PWD/ic}
rm -f /tmp/hr-last-caches.txt
./compile-daemon.sh
NEW=$(grep -E 'per-file-cache/.*\.a$' /tmp/hr-last-caches.txt 2>/dev/null | tail -1)
if [ -z "$NEW" ]; then
  echo "no new cache archive (see /tmp/hr-last-caches.txt)" >&2
  exit 1
fi
echo "user archive: $NEW"
rm -rf patch-current && mkdir patch-current
(cd patch-current && ar x "$NEW")
OBJ=$(ls patch-current/*.o | head -1)
cp "$OBJ" patch-current/patch.o
echo "object: $OBJ -> patch.o"
python3 "${RELOAD_PY:-$(dirname "$0")/../demo/reload.py}" "$PWD/patch-current/patch.o"
