#!/bin/bash
# Adding a trailing parameter with a default value, forced through as a hot reload (Issue #19, #27).
#
#   source <toolchain>/env.sh; [UDID=… WINDOW_RELOADS=50 LATENCY_RELOADS=20 CASES="C1 C2 …"] bash accept-default-param.sh [--window-only]
#
# The classifier still says WARM for a signature change (rule for HOT: Issue #28), so each case runs
# 'hotreload.sh reload --no-warm', which builds the patch and stops at the classifier, and then sends
# that patch by hand. Callee in ui.kt; callers in ui.kt, ui2.kt (another ui file) and the app module.
#   C1     top-level fun uiLabel() -> uiLabel(suffix = "!")               all callers show L2!, same process
#   C2     @Composable Badge(text, tick) -> Badge(text, tick, rim = "R")  new Badge everywhere, same process
#   C2off  C2 with KONAN_HR_FORCE_RECOMPOSE=0                             counter-example: the process may end
#   C3     final member describe() -> describe(prefix = "P")              all callers show P-D2, same process
#   BUSY   the main thread is blocked for 4 s (native sleep) while a patch arrives: "HR reload: NOT applied" after
#          KONAN_HR_MAIN_WAIT_MS, nothing applied, same process; sending again afterwards applies it.
#   LATENCY reload request -> forced frame applied, main-queue reload vs listener thread (KONAN_HR_RELOAD_ON_MAIN=0),
#          LATENCY_RELOADS switches each on a screen that recomposes once a second: the difference must stay < 1 frame.
#   Every case also runs a never-sleeping Kotlin loop on Dispatchers.Default that logs gaps (BGGAP): the stats count
#   the reloads during which that thread was stopped (stop-the-world kept).
#   WINDOW Badge reads a state that changes every frame and only Badge reads it, so every frame
#          recomposes Badge alone through its restart lambda; the signature is switched back and forth
#          WINDOW_RELOADS times (the new parameter is used in every recomposition). 0 crashes, same process.
#          Every reload must run on the main queue and invalidate there (via=reload, Issue #27).
#   WINDOW-DELAY  the same with the posted main-thread task delayed by 300 ms (KONAN_HR_TEST_TASK_DELAY_MS).
#   WINDOW-NOMAIN counter-example, not counted: KONAN_HR_RELOAD_ON_MAIN=0 (reload on the listener thread) with
#          the frame entry still on; a frame interrupted by the reload continues on the new code, so it may end.
#   WINDOW-NOFRAME counter-example, not counted: also KONAN_HR_TEST_NO_FRAME_ENTRY=1 (the behaviour before #27).
# Example sources are restored on exit; run 'hotreload.sh package' afterwards to rebuild the reference app.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE"
UDID=${UDID:-0B879832-98D9-49A5-AC00-8BAB579AFBB2}
WINDOW_RELOADS=${WINDOW_RELOADS:-50}
LATENCY_RELOADS=${LATENCY_RELOADS:-20}
CASES=${CASES:-C1 C2 C3 C2off BUSY LATENCY WINDOW WINDOW-DELAY WINDOW-NOMAIN WINDOW-NOFRAME}
export BUSY_FLAG=/tmp/hr-accept-busy-$UDID
want_case() { case " $CASES " in *" $1 "*) return 0;; esac; return 1; }
OUT=$HERE/build/regression/default-param
rm -rf "$OUT"; mkdir -p "$OUT/orig"
U=ui/src/main/kotlin/ui.kt; U2=ui/src/main/kotlin/ui2.kt; A=app/src/main/kotlin/main.kt
for f in "$U" "$U2" "$A"; do cp "$f" "$OUT/orig/$(basename "$f")"; done
restore() { for f in "$U" "$U2" "$A"; do cp "$OUT/orig/$(basename "$f")" "$f"; done; }
trap restore EXIT
app_pid() { pgrep -n -f "Devices/$UDID/.*ComposeHR\.app/ComposeHR"; }

setup() { # setup <tick: second|frame>
  restore
  python3 - "$1" <<'PY'
import os, sys
tick = sys.argv[1]
loop = ("kotlinx.coroutines.delay(1000)\n            t.value++" if tick == "second"
        else "androidx.compose.runtime.withFrameNanos { }\n            t.value++")
# Badge prints on every recomposition, so the new parameter is read in each one (also through the
# migrated restart lambda in the window). HR_TEST_GATE='if (seen[0] % 30 == 1) ' prints less often.
gate = os.environ.get("HR_TEST_GATE", "")
u = "ui/src/main/kotlin/ui.kt"; s = open(u).read()
s = s.replace("import androidx.compose.runtime.Composable\n",
              "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.LaunchedEffect\n"
              "import androidx.compose.runtime.State\nimport androidx.compose.runtime.mutableStateOf\n"
              "import androidx.compose.runtime.remember\nimport platform.Foundation.timeIntervalSince1970\n", 1)
s = s.rstrip() + '''

fun uiLabel(): String = "L1"

class Describer(val n: Int) {
    fun describe(): String = "D1-$n"
}

@Composable
fun Badge(text: String, tick: State<Int>) {
    val seen = remember { intArrayOf(0) }
    seen[0]++
    GATEprintln("BADGE tag=$text v=1 tick=${tick.value} seen=${seen[0]}")
    Text("BADGE $text", color = Color.White, fontSize = 12.sp)
}

@Composable
fun UiMark19() {
    // Only Badge reads t; UiMark19 has no parameters and does not read t.
    val t = remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            LOOP
        }
    }
    // #27: a Kotlin loop on another thread (Dispatchers.Default) that never sleeps; a gap of more than 20 ms means
    // it was stopped (a reload stops every Kotlin thread while code is swapped and instances are migrated).
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val start = kotlin.time.TimeSource.Monotonic.markNow()
            var last = 0L
            while (true) {
                val now = start.elapsedNow().inWholeMicroseconds
                if (now - last > 20_000L) {
                    val epochMs = (platform.Foundation.NSDate().timeIntervalSince1970 * 1000.0).toLong()
                    println("BGGAP ms=${(now - last) / 1000} epochMs=$epochMs")
                }
                last = now
            }
        }
    }
    // #27: when the flag file exists, block the main thread for 4 s (native sleep, like a hang).
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(100)
            if (platform.posix.access(BUSY_FLAG, platform.posix.F_OK) == 0) {
                platform.posix.remove(BUSY_FLAG)
                println("BUSY start")
                platform.posix.usleep(4_000_000u)
                println("BUSY end")
            }
        }
    }
    println("CALL19 file=ui label=${uiLabel()} desc=${Describer(1).describe()}")
    Badge("ui", t)
}

private const val BUSY_FLAG = "BUSYPATH"
'''.replace("LOOP", loop).replace("BUSYPATH", os.environ["BUSY_FLAG"]).replace("GATE", gate)
s = s.replace('''    Text("UI $UI_MARK", color = Color(0xFF7FD1AE), fontSize = 18.sp)
}''', '''    Text("UI $UI_MARK", color = Color(0xFF7FD1AE), fontSize = 18.sp)
    UiMark19()
}''', 1)
open(u, "w").write(s)
u2 = "ui/src/main/kotlin/ui2.kt"; s = open(u2).read()
s = s.replace('''    Text("UI2 $UI2_MARK", color = Color(0xFFFFB4A2), fontSize = 18.sp)
}''', '''    Text("UI2 $UI2_MARK", color = Color(0xFFFFB4A2), fontSize = 18.sp)
    println("CALL19 file=ui2 label=${uiLabel()} desc=${Describer(3).describe()}")
}''', 1)
open(u2, "w").write(s)
a = "app/src/main/kotlin/main.kt"; s = open(a).read()
s = s.replace("import expui.UiMark2\n", "import expui.UiMark2\nimport expui.Badge\nimport expui.Describer\nimport expui.uiLabel\n", 1)
s = s.replace('''                UiMark2(tick.value)
                Spacer(Modifier.height(16.dp))''', '''                UiMark2(tick.value)
                println("CALL19 file=app label=${uiLabel()} desc=${Describer(2).describe()}")
                Badge("app", tick)
                Spacer(Modifier.height(16.dp))''', 1)
open(a, "w").write(s)
PY
}
edit() { python3 - "$U" "$1" "$2" <<'PY'
import sys
p, a, b = sys.argv[1:]; s = open(p).read(); assert a in s, a; open(p, "w").write(s.replace(a, b, 1))
PY
}
launch() { # launch <KONAN_HR_FORCE_RECOMPOSE>
  bash hotreload.sh package > "$OUT/$CASE-package.out" 2>&1 || { echo "FAIL $CASE package"; exit 1; }
  pkill -f "$HERE/run.sh" 2>/dev/null; pkill -f "^bash run.sh" 2>/dev/null
  xcrun simctl terminate "$UDID" com.example.composehr 2>/dev/null; : > launch.log
  KONAN_HR_FORCE_RECOMPOSE=$1 bash -c "set -m; nohup bash '$HERE/run.sh' > '$OUT/$CASE-run.out' 2>&1 &"
  for _ in $(seq 1 150); do grep -q "CALL19 file=app" launch.log 2>/dev/null && break; sleep 0.5; done; sleep 3
  PID0=$(app_pid)
}
force() { # build the patch (classifier stops it), send it by hand, return the new log lines in $NEW
  bash hotreload.sh ui-klib > "$OUT/$CASE-reload.out" 2>&1 && bash hotreload.sh reload --no-warm >> "$OUT/$CASE-reload.out" 2>&1
  WARM=$(grep -m1 "^WARM" "$OUT/$CASE-reload.out" || true)
  local L0; L0=$(wc -l < launch.log)
  # Send by hand only when the classifier stopped the patch (WARM); a HOT verdict has already sent it
  # (switching back to v1 matches the classifier's baseline again).
  [ -n "$WARM" ] && python3 tools/reload.py "$PWD/build/patch/both.o" >> "$OUT/$CASE-reload.out" 2>&1
  sleep "${1:-6}"
  NEW=$(tr -d '\r' < launch.log | tail -n +$((L0 + 1)))
  echo "$NEW" >> "$OUT/$CASE-after.log"
}
to_v2() {
  edit 'fun Badge(text: String, tick: State<Int>) {' 'fun Badge(text: String, tick: State<Int>, rim: String = "R") {'
  edit 'v=1 tick' 'v=2 rim=$rim len=${rim.length} tick'
  edit 'Text("BADGE $text",' 'Text("BADGE $text ${rim.length}",'
}
to_v1() {
  edit 'fun Badge(text: String, tick: State<Int>, rim: String = "R") {' 'fun Badge(text: String, tick: State<Int>) {'
  edit 'v=2 rim=$rim len=${rim.length} tick' 'v=1 tick'
  edit 'Text("BADGE $text ${rim.length}",' 'Text("BADGE $text",'
}
stats() { # stats <log chunk file>: queue wait, run time, reload->new frame latency, stop-the-world coverage
  python3 - "$1" <<'PY'
import re, sys, statistics as st
lines = open(sys.argv[1], errors="replace").read().splitlines()
R = re.compile(r"HR reload: ran on the (main queue|listener thread) requestEpochMs=(\d+) waitMs=([\d.]+) runMs=([\d.]+)")
F = re.compile(r"HR forceRecompose .*frameMs=(\w+) totalMs=\w+ frameEpochMs=(\d+)")
G = re.compile(r"BGGAP ms=(\d+) epochMs=(\d+)")
reloads, lat, gaps = [], [], []
for i, l in enumerate(lines):
    m = R.search(l)
    if m:
        reloads.append((int(m.group(2)), float(m.group(3)), float(m.group(4))))
        for l2 in lines[i + 1:]:
            if R.search(l2):
                break
            f = F.search(l2)
            if f:
                if f.group(1).isdigit():
                    lat.append(int(f.group(2)) - int(m.group(2)))
                break
    g = G.search(l)
    if g:
        gaps.append((int(g.group(1)), int(g.group(2))))
def q(v):
    return f"p50={st.median(v):.1f} max={max(v):.1f}" if v else "n/a"
# The other thread is stopped from the start of the reload run to its end: a gap that ends within the run
# window (plus 100 ms of slack) and lasts at least half of the run.
covered = sum(1 for (r, w, u) in reloads
              if any(r + w <= e <= r + w + u + 100 and ms >= 0.5 * u for ms, e in gaps))
print(f"reloads={len(reloads)} waitMs {q([w for _, w, _ in reloads])} runMs {q([u for _, _, u in reloads])} "
      f"reload->new frame ms {q(lat)} (n={len(lat)}) other-thread stopped during the reload {covered}/{len(reloads)}")
PY
}
pass=0; total=0
rec() { total=$((total + 1)); [ "$2" = PASS ] && pass=$((pass + 1)); echo "$1 $2 $3" | tee -a "$OUT/summary.txt"; }
check_case() { # check_case <needle that must appear for every caller ...>
  local ok=PASS why="" n
  local pid; pid=$(app_pid)
  [ "$pid" = "$PID0" ] || { ok=FAIL; why="$why pid $PID0->${pid:-gone}"; }
  echo "$NEW" | grep -q -E "reload failed|Uncaught|HOOK class" && { ok=FAIL; why="$why error-in-log"; }
  for n in "$@"; do echo "$NEW" | grep -q -F "$n" || { ok=FAIL; why="$why missing:[$n]"; }; done
  rec "$CASE" "$ok" "classifier=[${WARM#WARM }]$why"
}

if [ "${1:-}" != "--window-only" ]; then
  want_case C1 && { CASE=C1; setup second; launch 1
  edit 'fun uiLabel(): String = "L1"' 'fun uiLabel(suffix: String = "!"): String = "L2$suffix"'; force
  check_case "CALL19 file=app label=L2!" "CALL19 file=ui2 label=L2!" "CALL19 file=ui label=L2!"; }

  want_case C2 && { CASE=C2; setup second; launch 1
  to_v2; force
  check_case "BADGE tag=app v=2 rim=R" "BADGE tag=ui v=2 rim=R"; }

  want_case C3 && { CASE=C3; setup second; launch 1
  edit '    fun describe(): String = "D1-$n"' '    fun describe(prefix: String = "P"): String = "$prefix-D2-$n"'; force
  check_case "desc=P-D2-2" "desc=P-D2-3" "desc=P-D2-1"; }

  want_case C2off && { CASE=C2off; setup second; launch 0
  to_v2; force
  echo "C2off INFO (counter-example, not counted) process $PID0 -> $(app_pid || true) $([ -z "$(app_pid)" ] && echo '(ended: a migrated restart lambda ran the new Badge with parameters that do not match)')" | tee -a "$OUT/summary.txt"; }

  # BUSY: the main thread is blocked for 4 s while a patch arrives. After KONAN_HR_MAIN_WAIT_MS (2 s) the runtime
  # must log "HR reload: NOT applied" and apply nothing (no hang, same process); sending again afterwards works.
  want_case BUSY && { CASE=BUSY; setup second; launch 1
  to_v2
  bash hotreload.sh ui-klib > "$OUT/$CASE-reload.out" 2>&1 && bash hotreload.sh reload --no-warm >> "$OUT/$CASE-reload.out" 2>&1
  L0=$(wc -l < launch.log); : > "$BUSY_FLAG"
  for _ in $(seq 1 50); do tail -n +$((L0 + 1)) launch.log | grep -q "BUSY start" && break; sleep 0.1; done
  python3 tools/reload.py "$PWD/build/patch/both.o" >> "$OUT/$CASE-reload.out" 2>&1; sleep 7
  NEW1=$(tr -d '\r' < launch.log | tail -n +$((L0 + 1))); L1=$(wc -l < launch.log)
  python3 tools/reload.py "$PWD/build/patch/both.o" >> "$OUT/$CASE-reload.out" 2>&1; sleep 5
  NEW2=$(tr -d '\r' < launch.log | tail -n +$((L1 + 1))); printf '%s\n--- resend ---\n%s\n' "$NEW1" "$NEW2" > "$OUT/$CASE-after.log"
  r=PASS; why=""
  echo "$NEW1" | grep -q "BUSY start" || { r=FAIL; why="$why main-not-blocked"; }
  echo "$NEW1" | grep -q "HR reload: NOT applied" || { r=FAIL; why="$why no-NOT-applied-line"; }
  echo "$NEW1" | grep -q -E "HR reload: ran|BADGE tag=ui v=2" && { r=FAIL; why="$why applied-while-blocked"; }
  [ "$(app_pid)" = "$PID0" ] || { r=FAIL; why="$why pid $PID0->$(app_pid)"; }
  echo "$NEW2" | grep -q "HR reload: ran on the main queue" && echo "$NEW2" | grep -q "BADGE tag=ui v=2" || { r=FAIL; why="$why resend-not-applied"; }
  rec "$CASE" "$r" "main thread blocked 4 s: $(echo "$NEW1" | grep -o 'HR reload: NOT applied[^(]*' | head -1); resend applied on the main queue$why"; }

  # LATENCY: the same screen (one recomposition per second), LATENCY_RELOADS signature switches with the reload on
  # the main queue and on the listener thread (KONAN_HR_RELOAD_ON_MAIN=0): reload request -> forced frame applied.
  latency() { # latency <case> <reload on main 1|0>
    CASE=$1; export SIMCTL_CHILD_KONAN_HR_RELOAD_ON_MAIN=$2; setup second; launch 1
    local LS i; LS=$(wc -l < launch.log)
    for i in $(seq 1 "$LATENCY_RELOADS"); do if [ $((i % 2)) = 1 ]; then to_v2; else to_v1; fi; force 3; done
    sleep 2; tr -d '\r' < launch.log | tail -n +$((LS + 1)) > "$OUT/$CASE-since.log"
    unset SIMCTL_CHILD_KONAN_HR_RELOAD_ON_MAIN
    echo "  $CASE $(stats "$OUT/$CASE-since.log") process $PID0 -> $(app_pid)" | tee -a "$OUT/summary.txt"
  }
  want_case LATENCY && {
    latency LATENCY-MAIN 1; on=$(stats "$OUT/LATENCY-MAIN-since.log"); pid_on_ok=$([ -n "$(app_pid)" ] && echo 1 || echo 0)
    latency LATENCY-LISTENER 0; off=$(stats "$OUT/LATENCY-LISTENER-since.log")
    med() { echo "$1" | sed -n 's/.*reload->new frame ms p50=\([0-9.]*\).*/\1/p'; }
    added=$(python3 -c "import sys; a, b = sys.argv[1:]; print(f'{float(a) - float(b):.1f}' if a and b else 'n/a')" "$(med "$on")" "$(med "$off")")
    r=PASS; [ "$pid_on_ok" = 1 ] || r=FAIL
    python3 -c "import sys; sys.exit(0 if sys.argv[1] != 'n/a' and float(sys.argv[1]) < 16.7 else 1)" "$added" || r=FAIL
    rec LATENCY "$r" "added reload->new frame latency of the main-queue reload (p50 difference): $added ms (one frame = 16.7 ms)"; }
fi

window() { # window <case> <task delay ms> <no frame entry 0|1> <reload on main queue 1|0> <counted 1|0>
  CASE=$1
  export SIMCTL_CHILD_KONAN_HR_TEST_TASK_DELAY_MS=$2 SIMCTL_CHILD_KONAN_HR_TEST_NO_FRAME_ENTRY=$3 SIMCTL_CHILD_KONAN_HR_RELOAD_ON_MAIN=$4
  setup frame; launch 1
  local crashes=0 done_reloads=0 vias="" i want seen LS
  LS=$(wc -l < launch.log)   # count only what happens from the first switch on
  for i in $(seq 1 "$WINDOW_RELOADS"); do
    if [ $((i % 2)) = 1 ]; then to_v2; want="v=2 rim=R"; else to_v1; want="v=1"; fi
    force 4
    done_reloads=$i
    vias="$vias $(echo "$NEW" | grep -o "via=[a-z]*" | tr '\n' ' ')"
    if [ -z "$(app_pid)" ]; then crashes=$((crashes + 1)); echo "  $CASE #$i process ended" | tee -a "$OUT/summary.txt"; break; fi
    echo "$NEW" | grep -q "BADGE tag=ui $want" && seen=yes || seen=no
    echo "  $CASE #$i $want shown=$seen" | tee -a "$OUT/summary.txt"
  done
  sleep 11; vias="$vias $(tr -d '\r' < launch.log | grep -o "via=[a-z]*" | tail -2 | tr '\n' ' ')"
  local reload frame task onmain
  local since; since=$(tr -d '\r' < launch.log | tail -n +$((LS + 1)))
  reload=$(echo "$since" | grep -c "via=reload"); frame=$(echo "$since" | grep -c "via=frame")
  task=$(echo "$since" | grep -c "via=task"); onmain=$(echo "$since" | grep -c "HR reload: ran on the main queue")
  unset SIMCTL_CHILD_KONAN_HR_TEST_TASK_DELAY_MS SIMCTL_CHILD_KONAN_HR_TEST_NO_FRAME_ENTRY SIMCTL_CHILD_KONAN_HR_RELOAD_ON_MAIN
  echo "$since" > "$OUT/$CASE-since.log"
  local stats="crashes=$crashes; reloads on the main queue=$onmain; invalidation via reload=$reload frame=$frame task=$task; $(stats "$OUT/$CASE-since.log")"
  if [ "$5" = 0 ]; then
    echo "$CASE INFO (counter-example, not counted) $done_reloads switches, $stats" | tee -a "$OUT/summary.txt"
    return
  fi
  local r=PASS why=""
  [ "$crashes" = 0 ] && [ "$(app_pid)" = "$PID0" ] || { r=FAIL; why=" crashed"; }
  [ "$onmain" = "$done_reloads" ] && [ "$reload" = "$done_reloads" ] || { r=FAIL; why="$why not-every-reload-on-main"; }
  rec "$CASE" "$r" "$done_reloads signature switches, every frame recomposes Badge alone; $stats$why"
}
want_case WINDOW && window WINDOW 0 0 1 1
want_case WINDOW-DELAY && window WINDOW-DELAY 300 0 1 1
want_case WINDOW-NOMAIN && window WINDOW-NOMAIN 300 0 0 0
want_case WINDOW-NOFRAME && window WINDOW-NOFRAME 300 1 0 0

pkill -f "$HERE/run.sh" 2>/dev/null; xcrun simctl terminate "$UDID" com.example.composehr 2>/dev/null
echo "summary: $pass/$total passed" | tee -a "$OUT/summary.txt"
[ "$pass" = "$total" ]
