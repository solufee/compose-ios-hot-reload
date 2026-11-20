#!/bin/bash
# Kotlin Multiplatform regression. Both modules use the source-set layout
# commonMain -> iosMain -> iosSimulatorArm64Main with expect/actual:
#   ui-kmp/src   (library, package expui)   built by the hr-klib.sock daemon
#   app-kmp/src  (program, package appkmp + main.kt) built by hr-compile.sock
# with -Xmulti-platform/-Xfragments (hotreload.sh UI_FRAGMENTS / APP_FRAGMENTS),
# then hot reload:
#   ui-common-body    commonMain function body (ui)              -> HOT
#   ui-actual-body    iosMain actual fun + actual class body (ui) -> HOT
#   app-common-body   commonMain function body (app)             -> HOT
#   app-actual-body   iosMain actual fun body (app)              -> HOT
#   ui-expect-sig     expect signature and its actual (ui)       -> WARM
# Optional: EXTRA_KLIB=<third-party ios_simulator_arm64 .klib> is added with -l
# (its directory joins the auto-cache roots); EXTRA_KLIB_USE=<Kotlin expression
# of type String> is called from the ui's iosMain and reloaded once.
#
#   source <toolchain>/env.sh; [UDID=… KONAN_HOT_RELOAD_PORT=…] bash accept-kmp.sh
#
# Sources are restored on exit. Each step checks the exit code, HOT/WARM, the
# PID, and the new values in the RENDER_UI (KMP[...]) or RENDER_APP (APPKMP[...]) line.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE"
UDID=${UDID:-0B879832-98D9-49A5-AC00-8BAB579AFBB2}
export UI_FRAGMENTS="commonMain iosMain iosSimulatorArm64Main"
export UI_FRAGMENT_REFINES="iosMain:commonMain iosSimulatorArm64Main:iosMain"
export UI_FRAGMENT_ROOT=$HERE/ui-kmp/src
export APP_FRAGMENTS="$UI_FRAGMENTS"
export APP_FRAGMENT_REFINES="$UI_FRAGMENT_REFINES"
export APP_FRAGMENT_ROOT=$HERE/app-kmp/src
export KALDO_HOT_PACKAGES=expui,appkmp
C=$UI_FRAGMENT_ROOT/commonMain/kotlin/ui.kt
I=$UI_FRAGMENT_ROOT/iosMain/kotlin/ui.ios.kt
AC=$APP_FRAGMENT_ROOT/commonMain/kotlin/appcommon.kt
AI=$APP_FRAGMENT_ROOT/iosMain/kotlin/app.ios.kt
OUTDIR=$HERE/build/regression/kmp
mkdir -p "$OUTDIR/orig"
for f in "$C" "$I" "$AC" "$AI"; do cp "$f" "$OUTDIR/orig/$(basename "$f")"; done
restore() {
  for f in "$C" "$I" "$AC" "$AI"; do cp "$OUTDIR/orig/$(basename "$f")" "$f"; done
  [ -n "${KLIST:-}" ] && rm -f "$KLIST"
  return 0
}
trap restore EXIT

if [ -n "${EXTRA_KLIB:-}" ]; then
  [ -f "$EXTRA_KLIB" ] || { echo "FAIL EXTRA_KLIB not found: $EXTRA_KLIB"; exit 1; }
  KLIST=$OUTDIR/klibs-extra.txt
  { cat "${KLIBLIST:?source the toolchain env.sh}"; echo "$EXTRA_KLIB"; } > "$KLIST"
  export KLIBLIST=$KLIST
  export KLIB_AUTO_CACHE_FROM="${KLIB_AUTO_CACHE_FROM:-}${KLIB_AUTO_CACHE_FROM:+:}$(cd "$(dirname "$EXTRA_KLIB")" && pwd)"
  echo "extra klib: $(basename "$EXTRA_KLIB"): $(unzip -p "$EXTRA_KLIB" default/manifest 2>/dev/null | grep -E '^(abi_version|compiler_version|metadata_version)=' | tr '\n' ' ')"
  python3 - "$I" "${EXTRA_KLIB_USE:?EXTRA_KLIB_USE is required with EXTRA_KLIB}" <<'PY'
import sys
p, expr = sys.argv[1], sys.argv[2]
s = open(p).read().rstrip() + f"\n\nfun extraLabel(): String = \"x1:\" + ({expr})\n"
s = s.replace('actual fun platformLabel(): String = "ios-1(', 'actual fun platformLabel(): String = extraLabel() + " ios-1(')
open(p, "w").write(s)
PY
fi

pass=0; total=0
rec() { total=$((total + 1)); if [ "$1" = PASS ]; then pass=$((pass + 1)); fi; echo "$2 $1 $3"; }
UI_LINE='^RENDER_UI .*KMP\['
APP_LINE='APPKMP\['

bash hotreload.sh ui-klib > "$OUTDIR/ui-klib.out" 2>&1; rc=$?
rec "$([ $rc = 0 ] && grep -q 'ui-klib: daemon' "$OUTDIR/ui-klib.out" && ! grep -q 'rerunning' "$OUTDIR/ui-klib.out" && echo PASS || echo FAIL)" \
  ui-klib-daemon "rc=$rc $(grep -o 'ui-klib: [a-z-]*' "$OUTDIR/ui-klib.out" | head -1)"
bash hotreload.sh package > "$OUTDIR/package.out" 2>&1; rc=$?
rec "$([ $rc = 0 ] && grep -q 'package: compile via daemon' "$OUTDIR/package.out" && ! grep -q 'rerunning' "$OUTDIR/package.out" && echo PASS || echo FAIL)" \
  package "rc=$rc $(grep -E '^PACKAGE' "$OUTDIR/package.out")"
[ $rc = 0 ] || { tail -20 "$OUTDIR/package.out"; exit 1; }

pkill -f "$HERE/run.sh" 2>/dev/null
xcrun simctl terminate "$UDID" com.example.composehr 2>/dev/null
: > launch.log
nohup bash "$HERE/run.sh" > "$OUTDIR/run.out" 2>&1 &
for _ in $(seq 1 150); do grep -q '^EH OK' launch.log 2>/dev/null && break; sleep 0.4; done
sleep 3
PID0=$(pgrep -n -f 'ComposeHR.app/ComposeHR')
EH=$(tr -d '\r' < launch.log | grep -o 'EH check: [0-9]* FDE' | head -1)
ULINE=$(tr -d '\r' < launch.log | grep -E "$UI_LINE" | tail -1)
ALINE=$(tr -d '\r' < launch.log | grep -E "$APP_LINE" | tail -1 | grep -o 'APPKMP\[.*\]')
rec "$(grep -q '^EH OK' launch.log && [ -n "$EH" ] && [ "${EH#EH check: 0 }" = "$EH" ] && [ -n "$ULINE" ] && [ -n "$ALINE" ] && echo PASS || echo FAIL)" \
  launch "pid=$PID0 $EH ${ULINE#RENDER_UI } $ALINE"

# step <label> <HOT|WARM> <line regex> <needle> <edits: KEY|old|new;;...>
step() {
  local label=$1 want=$2 pat=$3 needle=$4 edit=$5 L0 rc pid line ok=PASS why=""
  python3 - "$C" "$I" "$AC" "$AI" "$edit" <<'PY'
import sys
files = dict(zip(("C", "I", "AC", "AI"), sys.argv[1:5]))
for part in sys.argv[5].split(";;"):
    which, old, new = part.split("|")
    p = files[which]; s = open(p).read()
    assert old in s, (which, old)
    open(p, "w").write(s.replace(old, new))
PY
  L0=$(wc -l < launch.log)
  bash hotreload.sh ui-klib > "$OUTDIR/$label.out" 2>&1 && bash hotreload.sh reload >> "$OUTDIR/$label.out" 2>&1
  rc=$?
  sleep 3
  pid=$(pgrep -n -f 'ComposeHR.app/ComposeHR')
  # A warm restart truncates launch.log, so read all of it in that case.
  if [ "$want" = WARM ]; then L0=0; fi
  line=$(tr -d '\r' < launch.log | tail -n +$((L0 + 1)) | grep -E "$pat" | tail -1)
  [ "$rc" = 0 ] || { ok=FAIL; why="$why rc=$rc"; }
  if [ "$want" = HOT ]; then
    grep -q '^WARM' "$OUTDIR/$label.out" && { ok=FAIL; why="$why warm"; }
    [ "$pid" = "$PID0" ] || { ok=FAIL; why="$why pid=$pid"; }
  else
    grep -q '^WARM ' "$OUTDIR/$label.out" || { ok=FAIL; why="$why not-warm"; }
    grep -q '^WARM OK' "$OUTDIR/$label.out" || { ok=FAIL; why="$why no-WARM-OK"; }
    [ "$pid" != "$PID0" ] || { ok=FAIL; why="$why same-pid"; }
    PID0=$pid
  fi
  case "$line" in *"$needle"*) ;; *) ok=FAIL; why="$why line";; esac
  rec "$ok" "$label" "want=$want$([ -n "$why" ] && echo " why:$why") pid=$pid $(grep -E '^(WARM [a-z]|changed objects)' "$OUTDIR/$label.out" | tr '\n' ' ')$(echo "$line" | grep -o -E '(APP)?KMP\[.*\]')"
}

step ui-common-body  HOT  "$UI_LINE"  "common-2" 'C|fun commonLabel(): String = "common-1"|fun commonLabel(): String = "common-2"'
step ui-actual-body  HOT  "$UI_LINE"  "ios-2(iOS) hi-kmp-ios-2" 'I|ios-1(|ios-2(;;I|-ios-1"|-ios-2"'
step app-common-body HOT  "$APP_LINE" "appc-2 " 'AC|"appc-1"|"appc-2"'
step app-actual-body HOT  "$APP_LINE" "appios-2(" 'AI|"appios-1(|"appios-2('
step ui-expect-sig   WARM "$UI_LINE"  "ios-3[sig](iOS)" 'C|expect fun platformLabel(): String|expect fun platformLabel(tag: String): String;;C|${platformLabel()}|${platformLabel("sig")};;I|actual fun platformLabel(): String =|actual fun platformLabel(tag: String): String =;;I|ios-2(|ios-3[$tag]('
if [ -n "${EXTRA_KLIB:-}" ]; then
  step extra-klib-body HOT "$UI_LINE" "x2:" 'I|"x1:"|"x2:"'
fi

echo "summary: $pass/$total passed"
echo "note: sources restored; the app now runs the KMP modules; run 'hotreload.sh package' (without the *_FRAGMENTS variables) to go back"
[ "$pass" = "$total" ]
