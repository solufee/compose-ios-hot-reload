#!/bin/bash
# 200 consecutive daemon compiles; record RSS and post-GC heap.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}
export KLIBLIST=$PWD/klibs-jbmain4.txt AUTO_CACHE=$PWD/auto-cache-jbmain27 IC_DIR=$PWD/ic-jbmain27
N=${1:-200}
OUT=${2:-daemon-mem.log}
PID=$(pgrep -f HrCompileDaemon | head -1)
echo "pid=$PID n=$N" | tee "$OUT"

sample() {
  local i=$1
  local rss
  rss=$(ps -o rss= -p "$PID" | tr -d ' ')
  "$JAVA_HOME/bin/jcmd" "$PID" GC.run >/dev/null 2>&1 || true
  local heap
  heap=$("$JAVA_HOME/bin/jcmd" "$PID" GC.heap_info 2>/dev/null | sed '/Metaspace/,$d' | \
    python3 -c "
import re,sys
tot=0.0
for line in sys.stdin:
    m=re.search(r'used\\s+([0-9.]+)([KMG])', line)
    if m:
        v=float(m.group(1)); u=m.group(2)
        tot += v * {'K':1,'M':1024,'G':1024*1024}[u]
print(int(tot))
")
  echo "i=$i rss_mb=$((rss/1024)) heap_used_mb=$((heap/1024))" | tee -a "$OUT"
}
sample 0
for i in $(seq 1 "$N"); do
  python3 - "$i" <<'PY'
import re,sys
p='main.kt'; s=open(p).read()
s=re.sub(r'private const val BUILD_TAG = "[^"]*"', f'private const val BUILD_TAG = "mem{sys.argv[1]}"', s)
open(p,'w').write(s)
PY
  ./compile-daemon.sh >/dev/null || true
  if [ $((i % 20)) -eq 0 ]; then sample "$i"; fi
done
