#!/bin/bash
# 1b long session (REPORT §30.1): one app process, hot reloads for DURATION seconds (one every INTERVAL s),
# alternating ui and app text edits. Per reload: wall time from start of the reload to the
# first log line with the new marker (send -> new frame), plus RSS of the app and both daemons.
#   long-session.sh <example-gradle dir> <out csv> [DURATION=10800] [INTERVAL=30]
set -u
D=$1; CSV=$2; DURATION=${3:-10800}; INTERVAL=${4:-30}
cd "$D"
UDID=${UDID:?}; BUNDLE=com.example.composehr
U=ui/src/main/kotlin/ui.kt; A=app/src/main/kotlin/main.kt
cp $U /tmp/ls-ui.orig; cp $A /tmp/ls-app.orig
restore() { cp /tmp/ls-ui.orig $U; cp /tmp/ls-app.orig $A; }
trap restore EXIT
now_ms() { perl -MTime::HiRes=time -e 'printf "%d", time*1000'; }
rss_kb() { [ -n "$1" ] && ps -o rss= -p "$1" 2>/dev/null | tr -d ' ' || echo 0; }
PID=$(pgrep -n -f "Devices/$UDID/.*ComposeHR\.app/ComposeHR")
DC=$(ps -axo pid=,args= | awk -v s="$D/.hr/hr-compile.sock" 'index($0, s) && /HrCompileDaemon/ {print $1}' | head -1)
DK=$(ps -axo pid=,args= | awk -v s="$D/.hr/hr-klib.sock" 'index($0, s) && /HrCompileDaemon/ {print $1}' | head -1)
echo "i,epoch_ms,elapsed_s,module,rc,send_to_frame_ms,reload_ms,pid,app_rss_kb,compile_daemon_rss_kb,klib_daemon_rss_kb" > "$CSV"
T0=$(now_ms); i=0
while :; do
  el=$(( ($(now_ms) - T0) / 1000 )); [ "$el" -ge "$DURATION" ] && break
  i=$((i + 1))
  if [ $((i % 2)) = 1 ]; then mod=ui; mark="ls-u$i"; sed -i '' -E "s/const val UI_MARK = \"[^\"]*\"/const val UI_MARK = \"$mark\"/" $U; pat="RENDER_UI $mark"
  else mod=app; mark="ls-a$i"; sed -i '' -E "s/private const val APP_MARK = \"[^\"]*\"/private const val APP_MARK = \"$mark\"/" $A; pat="RENDER_APP $mark"; fi
  L0=$(wc -l < launch.log); t0=$(now_ms)
  { [ $mod = ui ] && bash hotreload.sh ui-klib > /dev/null 2>&1 || true; bash hotreload.sh reload --no-warm > /tmp/ls-reload.out 2>&1; }; rc=$?
  t1=$(now_ms); tf=""
  for _ in $(seq 1 100); do
    if tail -n +$((L0 + 1)) launch.log | tr -d '\r' | grep -q "$pat"; then tf=$(now_ms); break; fi
    sleep 0.1
  done
  s2f=$([ -n "$tf" ] && echo $((tf - t0)) || echo -1)
  p=$(pgrep -n -f "Devices/$UDID/.*ComposeHR\.app/ComposeHR")
  echo "$i,$t0,$el,$mod,$rc,$s2f,$((t1 - t0)),$p,$(rss_kb "$p"),$(rss_kb "$DC"),$(rss_kb "$DK")" >> "$CSV"
  [ "$p" != "$PID" ] && echo "process changed at reload $i ($PID -> $p)" >&2
  sleep $(( INTERVAL > (t1 - t0) / 1000 ? INTERVAL - (t1 - t0) / 1000 : 1 ))
done
echo "done: $i reloads in $(( ($(now_ms) - T0) / 1000 )) s"
