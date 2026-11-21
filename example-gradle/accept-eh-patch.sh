#!/bin/bash
# Regression for Issue #80: an exception that unwinds through hot reload patch code must be caught, not end the app.
#
#   source <toolchain>/env.sh; [UDID=… KONAN_HOT_RELOAD_PORT=… EDITS=5] bash accept-eh-patch.sh
#
# Writes ui/src/main/kotlin/eh80.kt (removed on exit) and one line in ui.kt (restored on exit), packages, launches, then
# changes one string in eh80.kt EDITS times; every change is a HOT patch of eh80.kt. In the patched file a called
# function throws and its caller catches:
#   cce    a failing cast (thrown by the runtime's downcast)       -> CCE
#   npe    `!!` on null (thrown by the runtime)                     -> NPE
#   my     a custom exception class declared in the patched file   -> MY
#   chain  thrown two patched functions down (thrower80 <- mid80)  -> CHAIN
# A throw caught in the same function becomes a jump and needs no unwinding, so every case goes through a call.
# Each step must be: reload exit 0, no WARM, same PID, no crash text in the log, and the new line
# `EH80 v=<n> cce=CCE npe=NPE my=MY chain=CHAIN` printed by the patched code.
# Without the fix (patch objects keep __LD,__compact_unwind, libKaldo registers no FDE for them) the first edit ends
# the app with "Uncaught Kotlin exception".
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE"
UDID=${UDID:?UDID is required}
EDITS=${EDITS:-5}
app_pid() { pgrep -n -f "Devices/$UDID/.*ComposeHR\.app/ComposeHR"; }
U=ui/src/main/kotlin/ui.kt
E=ui/src/main/kotlin/eh80.kt
OUTDIR=build/regression/eh80
mkdir -p "$OUTDIR"
cp "$U" "$OUTDIR/ui.kt.orig"
restore() { cp "$OUTDIR/ui.kt.orig" "$U"; rm -f "$E"; }
trap restore EXIT

cat > "$E" <<'KT'
package expui

class Eh80Box(val v: String)

class Eh80Error(message: String) : Exception(message)

fun thrower80(kind: Int, tag: String): String {
    val any: Any = tag
    return when (kind) {
        0 -> (any as Eh80Box).v
        1 -> tag.takeIf { it.isEmpty() }!!
        else -> throw Eh80Error("custom $tag")
    }
}

fun mid80(tag: String): String = thrower80(2, tag) + "-mid"

fun caught80(kind: Int, tag: String): String = try {
    thrower80(kind, tag)
} catch (e: ClassCastException) {
    "CCE"
} catch (e: NullPointerException) {
    "NPE"
} catch (e: Eh80Error) {
    "MY"
}

fun chain80(tag: String): String = try { mid80(tag) } catch (e: Eh80Error) { "CHAIN" }

fun eh80Line(): String {
    val v = "v0"
    return "EH80 v=$v cce=${caught80(0, v)} npe=${caught80(1, v)} my=${caught80(2, v)} chain=${chain80(v)}"
}
KT
python3 - "$U" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p).read()
s2 = re.sub(r'(\n    println\("RENDER_UI [^\n]*\n)', r'\1    println(eh80Line())\n', s, count=1)
if s2 == s:
    raise SystemExit("accept-eh-patch: no RENDER_UI println in ui.kt to hook")
open(p, "w").write(s2)
PY

bash hotreload.sh package > "$OUTDIR/package.out" 2>&1 || { echo "FAIL package (see $OUTDIR/package.out)"; exit 1; }
pkill -f "$HERE/run.sh" 2>/dev/null
xcrun simctl terminate "$UDID" com.example.composehr 2>/dev/null
# Another app listening on the reload port would receive every patch (seen once: a different app left running).
PORT=${KONAN_HOT_RELOAD_PORT:-5567}
sleep 1
busy=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | awk 'NR>1{print $1"("$2")"}' | sort -u | tr '\n' ' ')
[ -z "$busy" ] || { echo "FAIL port $PORT is already in use by: $busy"; exit 1; }
: > launch.log
nohup bash "$HERE/run.sh" > "$OUTDIR/run.out" 2>&1 &
for _ in $(seq 1 150); do grep -q '^EH OK' launch.log 2>/dev/null && grep -q '^EH80 ' launch.log && break; sleep 0.4; done
PID0=$(app_pid)
first=$(tr -d '\r' < launch.log | grep '^EH80 ' | head -1)
echo "launched pid=$PID0 first line: ${first:-none}"
[ -n "$PID0" ] && [ "$first" = "EH80 v=v0 cce=CCE npe=NPE my=MY chain=CHAIN" ] || { echo "FAIL launch"; exit 1; }

pass=0; total=0
for n in $(seq 1 "$EDITS"); do
  ok=1; why=""
  sed -i '' "s/val v = \"v$((n - 1))\"/val v = \"v$n\"/" "$E"
  L0=$(wc -l < launch.log)
  bash hotreload.sh ui-klib > "$OUTDIR/edit-$n.out" 2>&1 && bash hotreload.sh reload --no-warm >> "$OUTDIR/edit-$n.out" 2>&1
  rc=$?
  want="EH80 v=v$n cce=CCE npe=NPE my=MY chain=CHAIN"
  for _ in $(seq 1 30); do tr -d '\r' < launch.log | tail -n +$((L0 + 1)) | grep -qxF "$want" && break; sleep 0.3; done
  sleep 1
  new=$(tr -d '\r' < launch.log | tail -n +$((L0 + 1)))
  pid=$(app_pid)
  crash=$(printf '%s\n' "$new" | grep -c -E 'SIGSEGV|EXC_BAD|Uncaught|terminating|Hot-Reload failed')
  fdes=$(printf '%s\n' "$new" | grep -o 'patched [0-9]* FDEs, registered [0-9]*' | tail -1)
  line=$(printf '%s\n' "$new" | grep '^EH80 ' | tail -1)
  [ "$rc" = 0 ] || { ok=0; why="$why rc=$rc"; }
  grep -q '^WARM' "$OUTDIR/edit-$n.out" && { ok=0; why="$why warm"; }
  [ "$pid" = "$PID0" ] || { ok=0; why="$why pid=${pid:-gone}"; }
  [ "$crash" = 0 ] || { ok=0; why="$why crash"; }
  [ "$line" = "$want" ] || { ok=0; why="$why line=[${line}]"; }
  total=$((total + 1)); [ "$ok" = 1 ] && pass=$((pass + 1))
  echo "edit-$n $([ "$ok" = 1 ] && echo PASS || echo "FAIL:$why") rc=$rc pid=${pid:-gone} line=[${line}] patch EH: ${fdes:-no FDE registered}"
  [ -z "$pid" ] && break
done
echo "summary: $pass/$total passed (same process $PID0)"
echo "note: ui.kt restored and eh80.kt removed; run 'hotreload.sh package' before other tests"
[ "$pass" = "$total" ]
