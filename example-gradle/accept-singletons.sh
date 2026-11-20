#!/bin/bash
# Regression: composable lambdas added to / removed from an EXISTING file, i.e. new
# fields in Compose's ComposableSingletons$<File>Kt object (visible only in IR, so the
# classifier says HOT). Also records how `object` state behaves across reloads.
#
#   source <toolchain>/env.sh; [UDID=… KONAN_HOT_RELOAD_PORT=… ROUNDS=5] bash accept-singletons.sh
#
# Writes a test ui.kt (restored on exit), packages, launches, then reloads:
#   add-B, add-C, del-B, add-B2, edit-A, and ROUNDS-1 extra add/remove pairs.
# Every step must be: reload exit 0, no WARM line, same PID, no crash text in the
# log, exactly one `UICOUNTER init` (the file's objects are rebuilt by every reload,
# see TECHNICAL-NOTES §1.6), and on an add step the new lambda must run.
# Known, recorded but not failed: call sites whose arguments are all static (constant
# strings, singleton lambdas) are skipped by Compose, so edits inside an existing
# lambda and removals can leave old content on screen (LIMITATIONS 11).
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE"
UDID=${UDID:-0B879832-98D9-49A5-AC00-8BAB579AFBB2}
ROUNDS=${ROUNDS:-1}
U=ui/src/main/kotlin/ui.kt
OUTDIR=build/regression/singletons
mkdir -p "$OUTDIR"
cp "$U" "$OUTDIR/ui.kt.orig"
restore() { cp "$OUTDIR/ui.kt.orig" "$U"; }
trap restore EXIT

cat > "$U" <<'KT'
package expui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

const val UI_MARK = "ui-mf2"

object UiCounter {
    var n = 0
    init { println("UICOUNTER init") }
}

@Composable
fun Wrap(tag: String, content: @Composable () -> Unit) {
    println("WRAP $tag")
    content()
}

@Composable
fun UiMark(tick: Int) {
    UiCounter.n++
    println("RENDER_UI $UI_MARK n=${UiCounter.n} tick=$tick")
    Column {
        Text("UI $UI_MARK", color = Color(0xFF7FD1AE), fontSize = 18.sp)
        Wrap("a") { println("SHOW LAMBDA-A"); Text("LAMBDA-A", color = Color.White) }
        //SLOT
    }
}
KT

bash hotreload.sh package > "$OUTDIR/package.out" 2>&1 || { echo "FAIL package (see $OUTDIR/package.out)"; exit 1; }
pkill -f "$HERE/run.sh" 2>/dev/null
xcrun simctl terminate "$UDID" com.example.composehr 2>/dev/null
: > launch.log
nohup bash "$HERE/run.sh" > "$OUTDIR/run.out" 2>&1 &
for _ in $(seq 1 150); do grep -q '^EH OK' launch.log 2>/dev/null && break; sleep 0.4; done
sleep 3
PID0=$(pgrep -n -f 'ComposeHR.app/ComposeHR')
grep -q '^EH OK' launch.log && [ -n "$PID0" ] || { echo "FAIL launch"; exit 1; }
echo "launched pid=$PID0"

pass=0; total=0
step() { # step <label> <new lambda shown or -> <sed expression>
  local label=$1 want=$2 expr=$3 L0 rc new pid inits crash shown ok=1 why=""
  sed -i '' "$expr" "$U"
  L0=$(wc -l < launch.log)
  bash hotreload.sh ui-klib > "$OUTDIR/$label.out" 2>&1 && bash hotreload.sh reload --no-warm >> "$OUTDIR/$label.out" 2>&1
  rc=$?
  sleep 3.5
  new=$(tr -d '\r' < launch.log | tail -n +$((L0 + 1)))
  pid=$(pgrep -n -f 'ComposeHR.app/ComposeHR')
  inits=$(printf '%s\n' "$new" | grep -c 'UICOUNTER init')
  crash=$(printf '%s\n' "$new" | grep -c -E 'SIGSEGV|EXC_BAD|Uncaught|terminating|Hot-Reload failed')
  shown=$(printf '%s\n' "$new" | grep '^SHOW' | sed 's/^SHOW //' | sort -u | tr '\n' ' ')
  [ "$rc" = 0 ] || { ok=0; why="$why rc=$rc"; }
  grep -q '^WARM' "$OUTDIR/$label.out" && { ok=0; why="$why warm"; }
  [ "$pid" = "$PID0" ] || { ok=0; why="$why pid=$pid"; }
  [ "$crash" = 0 ] || { ok=0; why="$why crash"; }
  [ "$inits" = 1 ] || { ok=0; why="$why inits=$inits"; }
  if [ "$want" != - ]; then case " $shown " in *" $want "*) ;; *) ok=0; why="$why $want-not-run";; esac; fi
  total=$((total + 1)); [ "$ok" = 1 ] && pass=$((pass + 1))
  echo "$label $([ "$ok" = 1 ] && echo PASS || echo "FAIL:$why") rc=$rc pid=$pid inits=$inits shown=[${shown% }]"
}
add() { echo "s#//SLOT#Wrap(\"$1\") { println(\"SHOW LAMBDA-$1\"); Text(\"LAMBDA-$1\", color = Color.White) }\\n        //SLOT#"; }

step add-B  LAMBDA-B  "$(add B)"
step add-C  LAMBDA-C  "$(add C)"
step del-B  -         '/"LAMBDA-B"/d'
step add-B2 LAMBDA-B2 "$(add B2)"
step edit-A -         's#"LAMBDA-A"#"LAMBDA-A2"#; s#"SHOW LAMBDA-A"#"SHOW LAMBDA-A2"#'
for r in $(seq 2 "$ROUNDS"); do
  step "add-R$r" "LAMBDA-R$r" "$(add "R$r")"
  step "del-R$r" - "/\"LAMBDA-R$r\"/d"
done

xcrun simctl io "$UDID" screenshot "$OUTDIR/final.png" >/dev/null 2>&1
echo "final source lambdas: $(grep -o '"LAMBDA-[A-Z0-9]*"' "$U" | tr -d '"' | tr '\n' ' ')"
echo "screen (known, not checked): $OUTDIR/final.png; static call sites may still show older content"
echo "summary: $pass/$total passed (same process $PID0)"
echo "note: ui.kt restored; run 'hotreload.sh package' before other tests"
[ "$pass" = "$total" ]
