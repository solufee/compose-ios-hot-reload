#!/usr/bin/env bash
set -euo pipefail
KC="${KC:?set KC to the built kotlinc-native}"
OUT="${1:-host.kexe}"
"$KC" -target ios_simulator_arm64 -p program -g \
  -Xcompilation-scheme=split \
  -Xenable-incremental-compilation \
  -Xic-cache-dir="$PWD/ic" \
  -o "$OUT" host.kt
