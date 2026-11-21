#!/bin/bash
# #60 stage 2 item 3 gate: dyld loading the prelinked library image vs today's JIT bootstrap, same machine, same session.
# Usage: UDID=<sim> TOOLCHAIN_ENV=<toolchain>/env.sh run-gate.sh <out-dir> [runs]
# Needs a packaged example (build/ComposeHR.app, build/app) and build.sh <out-dir> run first.
set -u
O=$(cd "$1" && pwd); N=${2:-5}
HERE=$(cd "$(dirname "$0")" && pwd); EX=$(cd "$HERE/../../.." && pwd); TT=$(cd "$HERE/.." && pwd)
. "$TOOLCHAIN_ENV"; cd "$EX"
B=com.example.composehr
xcrun simctl terminate "$UDID" $B 2>/dev/null; xcrun simctl install "$UDID" build/ComposeHR.app
echo "== A. prelinked image: dlopen inside the app before main (map, rebase, bind, initializers), then exit"
for i in $(seq 1 "$N"); do
  SIMCTL_CHILD_DYLD_INSERT_LIBRARIES="$O/gate.dylib" SIMCTL_CHILD_HR_PRELINK_DYLIB="$O/libs.bundle.dylib" \
    perl -e 'alarm shift; exec @ARGV' 40 xcrun simctl launch --console-pty "$UDID" $B 2>&1 | tr -d '\r' | grep '^PRELINK-GATE' | sed "s/^/run $i: /"
done
echo "== B. today's JIT path (run.sh), 'bootstrap payloads registered' -> 'bootstrap objects loaded' from the stamped log"
for i in $(seq 1 "$N"); do
  xcrun simctl terminate "$UDID" $B 2>/dev/null; sleep 1
  rm -f "$O/stop"; : > "$O/launch.log"
  python3 "$TT/watch-log.py" "$O/launch.log" "$O/jit$i.tl" "$O/stop" & WP=$!
  LAUNCH_LOG="$O/launch.log" SKIP_INSTALL=1 bash run.sh > "$O/jit-run.out" 2>&1 & RP=$!
  for _ in $(seq 1 600); do grep -q RENDER_APP "$O/launch.log" 2>/dev/null && break; sleep 0.1; done
  sleep 0.5; touch "$O/stop"; wait $WP
  xcrun simctl terminate "$UDID" $B 2>/dev/null; kill $RP 2>/dev/null; wait $RP 2>/dev/null
  python3 - "$O/jit$i.tl" "$i" <<'PY'
import re, sys
t = {}
for l in open(sys.argv[1], errors='replace'):
    p = l.rstrip('\n').split('\t', 1)
    if len(p) < 2: continue
    for k, pat in (('boot', r'^HR LoadBootstrap'), ('reg', r'bootstrap payloads registered'), ('load', r'bootstrap objects loaded')):
        if k not in t and re.search(pat, p[1].strip()): t[k] = float(p[0])
ms = lambda a, b: round((t[b] - t[a]) * 1000) if a in t and b in t else None
print(f"run {sys.argv[2]}: bootstrap_register_ms={ms('boot','reg')} bootstrap_load_ms={ms('reg','load')}")
PY
done
