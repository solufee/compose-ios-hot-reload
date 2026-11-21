#!/bin/bash
# #60 (b): sample the example app's cold start (measurement only): main thread, 1 ms interval, 10 s from process start.
# Usage: TOOLCHAIN_ENV=<toolchain>/env.sh UDID=<sim> sample-coldstart.sh <out-dir> [runs]   (set SKIP_PKG=1 to reuse build/)
# Then: symtree.py <out>/sample1.txt <out>/perf1.map 'Konan_run_start.*\+ 164' 3 16
set -u
O=$1; N=${2:-2}; mkdir -p "$O"
HERE=$(cd "$(dirname "$0")" && pwd); EX=$(cd "$HERE/../.." && pwd)
. "$TOOLCHAIN_ENV"; cd "$EX"
bash ../daemon/daemon-start.sh --force >/dev/null 2>&1
[ -n "${SKIP_PKG:-}" ] || { bash hotreload.sh package > "$O/package.out" 2>&1 || exit 1; }
for n in $(seq 1 "$N"); do
  xcrun simctl terminate "$UDID" com.example.composehr 2>/dev/null; : > launch.log
  bash -c "set -m; nohup bash run.sh > '$O/run$n.out' 2>&1 & echo \$! > '$O/run$n.pid'"
  P=""; for _ in $(seq 1 3000); do P=$(pgrep -n -f "Devices/$UDID/.*ComposeHR\.app/ComposeHR$"); [ -n "$P" ] && break; sleep 0.01; done
  echo "run $n pid $P"; sample "$P" 10 1 -mayDie -file "$O/sample$n.txt" > /dev/null 2>&1
  sleep 2; tr -d '\r' < launch.log > "$O/launch$n.log"; cp "/tmp/perf-$P.map" "$O/perf$n.map" 2>/dev/null
  kill "$(cat "$O/run$n.pid")" 2>/dev/null
done
xcrun simctl terminate "$UDID" com.example.composehr 2>/dev/null
