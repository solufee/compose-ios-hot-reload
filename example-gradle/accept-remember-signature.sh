#!/bin/bash
# remember across signature changes (Issue #29): signature-independent group keys with a remember-shape guard.
#
#   source <toolchain>/env.sh; [UDID=… HR_STABLE_KEYS=0] bash accept-remember-signature.sh
#
# Needs a Compose compiler with the HotReloadStableKeys feature flag (toolchain .9 on); hotreload.sh passes it unless
# HR_STABLE_KEYS=0 (then this script is the control: every signature case resets, which it reports as INFO).
# Badge(text, tick) keeps `val seen = remember { intArrayOf(0) }` and counts its recompositions (tick once a second);
# each case starts a fresh app, reads the counter, edits ui.kt, hot reloads (sending by hand when the classifier says WARM).
#   K1 trailing default parameter added                       counter continues
#   K5 parameter without default, the caller passes a value   counter continues
#   K6 parameter type String -> Int (caller too)              counter continues
#   K4 K1, then a body edit                                   counter continues through both
#   K2 remember type IntArray -> LongArray                    counter restarts at 1 (safe reset), same process
#   K3 a second remember inserted before the first            counter restarts at 1, the new remember holds its own value
#   K7 two same-named overloads in one file, body edit        both keep their own counters
set -u
HERE=$(cd "$(dirname "$0")" && pwd); cd "$HERE"
OUT=$HERE/build/regression/remember-signature; rm -rf "$OUT"; mkdir -p "$OUT"
export UDID=${UDID:-0B879832-98D9-49A5-AC00-8BAB579AFBB2}
STABLE=${HR_STABLE_KEYS:-1}
U=ui/src/main/kotlin/ui.kt; U2=ui/src/main/kotlin/ui2.kt; A=app/src/main/kotlin/main.kt
mkdir -p "$OUT/orig"; for f in "$U" "$U2" "$A"; do cp "$f" "$OUT/orig/$(basename "$f")"; done
restore() { for f in "$U" "$U2" "$A"; do cp "$OUT/orig/$(basename "$f")" "$f"; done; }
trap restore EXIT
app_pid() { pgrep -n -f "Devices/$UDID/.*ComposeHR\.app/ComposeHR"; }
edit() { python3 - "$U" "$1" "$2" <<'PY'
import sys
p, a, b = sys.argv[1:]; s = open(p).read(); assert a in s, a; open(p, "w").write(s.replace(a, b, 1))
PY
}
setup() {
  restore
  python3 - <<'PY'
u = "ui/src/main/kotlin/ui.kt"; s = open(u).read()
s = s.replace("import androidx.compose.runtime.Composable\n",
              "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.LaunchedEffect\n"
              "import androidx.compose.runtime.State\nimport androidx.compose.runtime.mutableStateOf\n"
              "import androidx.compose.runtime.remember\n", 1)
s = s.rstrip() + '''

@Composable
fun Badge(text: String, tick: State<Int>) {
    val seen = remember { intArrayOf(0) }
    seen[0]++
    println("BADGE tag=$text v=1 tick=${tick.value} seen=${seen[0]}")
    Text("BADGE $text", color = Color.White, fontSize = 12.sp)
}

@Composable
fun UiMark29() {
    val t = remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            t.value++
        }
    }
    Badge("ui", t)
}
'''
s = s.replace('''    Text("UI $UI_MARK", color = Color(0xFF7FD1AE), fontSize = 18.sp)
}''', '''    Text("UI $UI_MARK", color = Color(0xFF7FD1AE), fontSize = 18.sp)
    UiMark29()
}''', 1)
open(u, "w").write(s)
PY
}
launch() {
  bash hotreload.sh package > "$OUT/$CASE-package.out" 2>&1 || { echo "$CASE package failed"; tail -5 "$OUT/$CASE-package.out"; return 1; }
  pkill -f "^bash run.sh" 2>/dev/null; xcrun simctl terminate "$UDID" com.example.composehr 2>/dev/null; : > launch.log
  bash -c "set -m; nohup bash run.sh > '$OUT/$CASE-run.out' 2>&1 &"
  for _ in $(seq 1 150); do grep -q "BADGE tag=ui" launch.log 2>/dev/null && break; sleep 0.5; done; sleep 6
  PID0=$(app_pid)
}
reload() { # build + send (by hand when the classifier says WARM), then collect new lines
  bash hotreload.sh ui-klib > "$OUT/$CASE-reload.out" 2>&1 && bash hotreload.sh reload --no-warm >> "$OUT/$CASE-reload.out" 2>&1
  local L0; L0=$(wc -l < launch.log)
  grep -q "^WARM" "$OUT/$CASE-reload.out" && python3 tools/reload.py "$PWD/build/patch/both.o" >> "$OUT/$CASE-reload.out" 2>&1
  sleep 5
  NEW=$(tr -d '\r' < launch.log | tail -n +$((L0 + 1))); echo "$NEW" >> "$OUT/$CASE-after.log"
}
seen_before() { tr -d '\r' < launch.log | grep -o "BADGE tag=${1:-ui} v=1 tick=[0-9]* seen=[0-9]*" | tail -1 | sed 's/.*seen=//'; }
pass=0; total=0
report() { # report <counter before> <expect: continue|reset> [tag]
  local before=$1 expect=$2 tag=${3:-ui} first after pid r=PASS why=""; pid=$(app_pid)
  first=$(echo "$NEW" | grep -o "BADGE tag=$tag v=[0-9].* seen=[0-9]*" | head -1 | sed 's/.*seen=//')
  after=$(echo "$NEW" | grep -o "BADGE tag=$tag v=[0-9].* seen=[0-9]*" | tail -1 | sed 's/.*seen=//')
  [ "$pid" = "$PID0" ] || { r=FAIL; why="$why process-ended"; }
  [ -n "$first" ] || { r=FAIL; why="$why no-output"; }
  if [ "$expect" = continue ] && [ -n "$first" ] && [ "$first" -le "$before" ]; then r=FAIL; why="$why reset"; fi
  if [ "$expect" = reset ] && [ -n "$first" ] && [ "$first" != 1 ]; then r=FAIL; why="$why kept-old-slot"; fi
  local line="$CASE/$tag expect=$expect seen before=$before first-after=${first:-none} last-after=${after:-none} pid $PID0->${pid:-gone}$why"
  if [ "$STABLE" = 0 ]; then echo "$line INFO(control, stable keys off: $r)" | tee -a "$OUT/summary.txt"; return; fi
  total=$((total + 1)); [ "$r" = PASS ] && pass=$((pass + 1)); echo "$line $r" | tee -a "$OUT/summary.txt"
}
CASE=K1; setup; launch; b=$(seen_before)
edit 'fun Badge(text: String, tick: State<Int>) {' 'fun Badge(text: String, tick: State<Int>, rim: String = "R") {'
edit 'v=1 tick' 'v=2 rim=$rim tick'; reload; report "$b" continue
CASE=K2; setup; launch; b=$(seen_before)
edit 'remember { intArrayOf(0) }' 'remember { longArrayOf(0L) }'; edit 'v=1 tick' 'v=2 tick'; reload; report "$b" reset
CASE=K3; setup; launch; b=$(seen_before)
edit '    val seen = remember { intArrayOf(0) }' '    val extra = remember { "extra" }
    val seen = remember { intArrayOf(0) }'; edit 'v=1 tick' 'v=2 extra=$extra tick'; reload; report "$b" reset
echo "$NEW" | grep -q 'extra=extra' || echo "K3 note: the new remember did not show its own value" | tee -a "$OUT/summary.txt"
CASE=K4; setup; launch; b=$(seen_before)
edit 'fun Badge(text: String, tick: State<Int>) {' 'fun Badge(text: String, tick: State<Int>, rim: String = "R") {'
edit 'v=1 tick' 'v=2 rim=$rim tick'; reload; report "$b" continue
b=$(echo "$NEW" | grep -o "BADGE tag=ui v=2.* seen=[0-9]*" | tail -1 | sed 's/.*seen=//')
edit 'v=2 rim=$rim tick' 'v=3 rim=$rim tick'; reload; report "$b" continue
# K5: a new parameter without a default, the caller passes a value (the callee then checks it with changed(), a slot)
CASE=K5; setup; launch; b=$(seen_before)
edit 'fun Badge(text: String, tick: State<Int>) {' 'fun Badge(text: String, tick: State<Int>, rim: String) {'
edit '    Badge("ui", t)' '    Badge("ui", t, "Q")'
edit 'v=1 tick' 'v=2 rim=$rim tick'; reload; report "$b" continue
# K6: parameter type changes (String -> Int) at the same position
CASE=K6; setup; launch; b=$(seen_before)
edit 'fun Badge(text: String, tick: State<Int>) {' 'fun Badge(text: Int, tick: State<Int>) {'
edit '    Badge("ui", t)' '    Badge(7, t)'
edit 'v=1 tick' 'v=2 tick'; reload; report "$b" continue 7
# K7: two same-named overloads in one file, each with its own counter; a body edit keeps both apart
CASE=K7; setup
python3 - <<'PY'
u = "ui/src/main/kotlin/ui.kt"; s = open(u).read()
s = s.replace("    Badge(\"ui\", t)\n}", "    Badge(\"ui\", t)\n    Badge(2, t)\n}", 1)
s = s.rstrip() + '''

@Composable
fun Badge(n: Int, tick: State<Int>) {
    val seen = remember { intArrayOf(100) }
    seen[0]++
    println("BADGE tag=n$n v=1 tick=${tick.value} seen=${seen[0]}")
}
'''
open(u, "w").write(s)
PY
launch; b=$(seen_before); b2=$(seen_before n2)
edit 'v=1 tick' 'v=2 tick'; edit 'println("BADGE tag=n$n v=1' 'println("BADGE tag=n$n v=2'; reload
report "$b" continue; report "$b2" continue n2
pkill -f "^bash run.sh" 2>/dev/null; xcrun simctl terminate "$UDID" com.example.composehr 2>/dev/null
grep -h "featureFlag=HotReloadStableKeys" "$OUT"/*-package.out >/dev/null 2>&1; echo "stable keys: HR_STABLE_KEYS=$STABLE" | tee -a "$OUT/summary.txt"
echo "summary: $pass/$total passed" | tee -a "$OUT/summary.txt"
[ "$STABLE" = 0 ] || [ "$pass" = "$total" ]
