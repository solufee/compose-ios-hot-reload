#!/bin/bash
# Hot-reload build driver for the Gradle project.
#   hotreload.sh ui-klib   compile ui.kt to build/ui/ui.klib
#   hotreload.sh package   compile app (split) + merge payloads + embed + assemble .app
#   hotreload.sh reload    compile changed caches, check symbols, merge with ld -r, send
set -e
ROOT=$(cd "$(dirname "$0")" && pwd)
# One config entry: the same defaults the Gradle task and run.sh use.
# Export the values: merge-payloads.py and the compiler read them from the
# environment, not from this shell.
if [ -f "$ROOT/hotreload.env" ]; then
  set -a
  . "$ROOT/hotreload.env"
  set +a
fi
KOTLIN_DIST=${KOTLIN_DIST:?KOTLIN_DIST is required (see README)}
KC=$KOTLIN_DIST/bin/kotlinc-native
PLUGIN=${COMPOSE_PLUGIN_JAR:?COMPOSE_PLUGIN_JAR is required (see README)}
# Issue #29: in hot reload builds a composable's group key leaves out its parameter list (plus a guard on the remember
# slots), so remember state survives signature changes. Only when the toolchain's Compose compiler knows the flag
# (toolchain .9 on); HR_STABLE_KEYS=0 turns it off. Every compile of one app must use the same setting.
COMPOSE_FLAGS=()
if [ "${HR_STABLE_KEYS:-1}" != 0 ] && unzip -p "$PLUGIN" androidx/compose/compiler/plugins/kotlin/FeatureFlag.class 2>/dev/null | LC_ALL=C grep -aq HotReloadStableKeys; then
  COMPOSE_FLAGS=(-P "plugin:androidx.compose.compiler.plugins.kotlin:featureFlag=HotReloadStableKeys")
fi
KLIBLIST=${KLIBLIST:-$ROOT/tools/klibs.txt}
BUILD=${BUILD_DIR:-$ROOT/build}
IC_DIR=${IC_DIR:-$BUILD/ic}
AUTO_CACHE=${AUTO_CACHE:-$BUILD/auto-cache}
APP_SRC=${APP_SRC:-$ROOT/app/src/main/kotlin/main.kt}
APP_SRC_DIR=${APP_SRC_DIR:-$(dirname "$APP_SRC")}
UI_SRC_DIR=${UI_SRC_DIR:-$ROOT/ui/src/main/kotlin}
# Optional Kotlin Multiplatform layout, per module: one directory per fragment,
# <root>/<fragment>/kotlin (the Gradle source-set layout).
#   UI_FRAGMENTS="commonMain iosMain iosSimulatorArm64Main"   (all fragments)
#   UI_FRAGMENT_REFINES="iosMain:commonMain iosSimulatorArm64Main:iosMain"
#   UI_FRAGMENT_ROOT=<dir>   (APP_FRAGMENTS, APP_FRAGMENT_REFINES, APP_FRAGMENT_ROOT
#                             do the same for the app module instead of APP_SRC)
# The compiler gets -Xmulti-platform, -Xfragments, -Xfragment-refines and one
# -Xfragment-sources=<fragment>:<file> per file; the files are also free
# arguments. All paths are absolute, so the compile daemons get the same
# arguments as kotlinc-native. The ui result is an ordinary ios_simulator_arm64
# klib. Packages of the app's non-main files must be listed in
# KALDO_HOT_PACKAGES like the ui packages (merge-payloads picks hot symbols by package).
UI_FRAGMENTS=${UI_FRAGMENTS:-}
UI_FRAGMENT_REFINES=${UI_FRAGMENT_REFINES:-}
if [ -n "$UI_FRAGMENTS" ]; then
  UI_FRAGMENT_ROOT=$(cd "${UI_FRAGMENT_ROOT:?UI_FRAGMENT_ROOT is required with UI_FRAGMENTS}" && pwd)
  UI_SRC_DIR=$UI_FRAGMENT_ROOT   # staleness checks scan every fragment
fi
APP_FRAGMENTS=${APP_FRAGMENTS:-}
APP_FRAGMENT_REFINES=${APP_FRAGMENT_REFINES:-}
if [ -n "$APP_FRAGMENTS" ]; then
  APP_FRAGMENT_ROOT=$(cd "${APP_FRAGMENT_ROOT:?APP_FRAGMENT_ROOT is required with APP_FRAGMENTS}" && pwd)
  APP_SRC_DIR=$APP_FRAGMENT_ROOT  # the classifier's "app sources changed" check
fi
OUT=${OUT:-$ROOT/build/ComposeHR}
STAMP_FILE=$BUILD/.reload-start
# Compatibility classifier (interface v7). Baseline snapshots live next to the
# build output; the app klib is only rebuilt when app sources change.
COMPAT_DIR=${COMPAT_DIR:-$BUILD/compat}
CLASSIFIER=${COMPAT_CLASSIFIER:-$ROOT/tools/compat-classify.py}
[ -f "$CLASSIFIER" ] || CLASSIFIER=$(cd "$ROOT/.." && pwd)/scripts/compat-classify.py
KLIB_TOOL=${KLIB_TOOL:-$KOTLIN_DIST/bin/klib}
# Compile daemons (interface v8): both sockets default to <HRC_SOCKET_DIR or
# $ROOT/.hr>, the directory daemon/daemon-start.sh uses. A missing socket falls
# back to starting kotlinc-native (correct, ~3 s slower per reload).
HRC_SOCKET_DIR=${HRC_SOCKET_DIR:-$ROOT/.hr}
HRC_SOCKET=${HRC_SOCKET:-$HRC_SOCKET_DIR/hr-compile.sock}
HRC_KLIB_SOCKET=${HRC_KLIB_SOCKET:-$HRC_SOCKET_DIR/hr-klib.sock}
UDID=${UDID:-0B879832-98D9-49A5-AC00-8BAB579AFBB2}
BUNDLE=${BUNDLE:-com.example.composehr}

# Every klib in the list except the Kotlin/Native dist's own stdlib/platform
# klibs (those are implicit). Works for Gradle-cache klibs and for klibs
# unpacked from a toolchain bundle (interfaceVersion 6).
KLIBS=()
while IFS= read -r k; do
  case "$k" in
    ""|"#"*|*/klib/common/*|*/klib/platform/*) ;;
    *) KLIBS+=("-l" "$k") ;;
  esac
done < "$KLIBLIST"
UIKIT_UTILS=${UIKIT_UTILS:-$ROOT/tools/libCMPUIKitObjcUtils.a}
# Colon-separated roots whose klibs get per-file caches built automatically.
KLIB_AUTO_CACHE_FROM=${KLIB_AUTO_CACHE_FROM:-~/.gradle/caches}

# fragment_args <root> "<fragments>" "<refines>": sets FRAG_ARGS to the
# multiplatform arguments for <root>/<fragment>/kotlin/**/*.kt.
fragment_args() {
  local root=$1 frags=$2 refines=$3 f r src names="" files=() fsrc=() refs=()
  for f in $frags; do
    names="$names${names:+,}$f"
    while IFS= read -r src; do
      files+=("$src")
      fsrc+=("-Xfragment-sources=$f:$src")
    done < <(find "$root/$f/kotlin" -name '*.kt' 2>/dev/null | sort)
  done
  for r in $refines; do refs+=("-Xfragment-refines=$r"); done
  [ ${#files[@]} -gt 0 ] || { echo "no .kt files under $root/{${frags// /,}}/kotlin" >&2; return 1; }
  FRAG_ARGS=(-Xmulti-platform -Xexpect-actual-classes "-Xfragments=$names" ${refs[@]+"${refs[@]}"} "${fsrc[@]}" "${files[@]}")
}

# Sets UI_SRC_ARGS / APP_SRC_ARGS: the plain sources, or the fragment arguments.
ui_source_args() {
  UI_SRC_ARGS=("$UI_SRC_DIR")
  [ -n "$UI_FRAGMENTS" ] || return 0
  fragment_args "$UI_FRAGMENT_ROOT" "$UI_FRAGMENTS" "$UI_FRAGMENT_REFINES" || return 1
  UI_SRC_ARGS=("${FRAG_ARGS[@]}")
}
app_source_args() {
  APP_SRC_ARGS=("$APP_SRC")
  [ -n "$APP_FRAGMENTS" ] || return 0
  fragment_args "$APP_FRAGMENT_ROOT" "$APP_FRAGMENTS" "$APP_FRAGMENT_REFINES" || return 1
  APP_SRC_ARGS=("${FRAG_ARGS[@]}")
}

auto_cache_args() {
  local IFS=: d
  for d in $KLIB_AUTO_CACHE_FROM; do echo "-Xauto-cache-from=$d"; done
}

common_args() {
  echo -target ios_simulator_arm64 -p program -g \
    -Xcompilation-scheme=split \
    -Xenable-incremental-compilation -Xic-cache-dir="$IC_DIR" \
    -Xbinary=perFileCacheForStdlib=false \
    $(auto_cache_args) \
    -Xauto-cache-dir="$AUTO_CACHE" \
    -Xplugin="$PLUGIN" ${COMPOSE_FLAGS[@]+"${COMPOSE_FLAGS[@]}"} \
    -opt-in=kotlinx.cinterop.ExperimentalForeignApi \
    -linker-option=-framework -linker-option=ImageIO \
    -linker-option=-Wl,-no_warn_duplicate_libraries \
    -linker-option=-force_load -linker-option="$UIKIT_UTILS"
}

# --- compatibility classifier (v7; v8: daemon, cache, runs beside the compile) --

now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time*1000'; }

# Full (IR-bearing) library klib of the app module, only for the classifier:
# inline/suspend bodies need IR. Built by the hr-klib daemon when it is up.
# No auto-cache arguments: a library compile does not use them, and the
# parallel cache-only compile writes to the same auto-cache directory.
build_app_klib() {
  mkdir -p "$COMPAT_DIR"
  rm -f "$COMPAT_DIR/app.klib"
  app_source_args || return 2
  local args=(-target ios_simulator_arm64 -p library -g
    -Xplugin="$PLUGIN" ${COMPOSE_FLAGS[@]+"${COMPOSE_FLAGS[@]}"} -opt-in=kotlinx.cinterop.ExperimentalForeignApi
    "${KLIBS[@]}" -l "$BUILD/ui/ui.klib" "${APP_SRC_ARGS[@]}" -o "$COMPAT_DIR/app")
  if [ -S "$HRC_KLIB_SOCKET" ]; then
    python3 "$ROOT/tools/hrc.py" "$HRC_KLIB_SOCKET" "${args[@]}" >/dev/null
  else
    "$KC" "${args[@]}" >/dev/null
  fi
}

# Sets CLS_ARGS (an array, so paths with spaces survive).
classifier_args() {
  CLS_ARGS=(--baseline-dir "$COMPAT_DIR" --klib-tool "$KLIB_TOOL" --cache-dir "$COMPAT_DIR/cache")
  [ -S "$HRC_KLIB_SOCKET" ] && CLS_ARGS+=(--daemon "$HRC_KLIB_SOCKET")
  return 0
}

app_sources_changed() {
  [ -f "$COMPAT_DIR/app.stamp" ] || return 0
  [ -n "$(find "$APP_SRC_DIR" -name '*.kt' -newer "$COMPAT_DIR/app.stamp" -print -quit 2>/dev/null)" ]
}

# Write the baseline snapshots for the code that `package` just built.
compat_baseline() {
  mkdir -p "$COMPAT_DIR"
  build_app_klib
  classifier_args
  python3 "$CLASSIFIER" baseline "${CLS_ARGS[@]}" \
    --module "ui=$BUILD/ui/ui.klib" --module "app=$COMPAT_DIR/app.klib"
  rm -f "$COMPAT_DIR/ui.new.json" "$COMPAT_DIR/app.new.json"
  # The stamp marks the app sources that app.json describes.
  touch "$COMPAT_DIR/app.stamp"
  echo "compat: baseline ui=$COMPAT_DIR/ui.json app=$COMPAT_DIR/app.json"
}

# Compare the new klibs against the running baseline. Prints one `WARM ...`
# line per reason and a timing line; returns 3 when a warm restart is
# required, 0 when the change can be hot replaced, 2 on a tool error.
compat_check() {
  if [ ! -f "$COMPAT_DIR/ui.json" ] || [ ! -f "$COMPAT_DIR/app.json" ]; then
    echo "compat: no baseline snapshots; run package first (skipping classifier)" >&2
    return 0
  fi
  [ -f "$BUILD/ui/ui.klib" ] || { echo "compat: ui.klib missing" >&2; return 2; }
  local t0 t1 app_mod="app=-"
  t0=$(now_ms)
  if app_sources_changed; then
    echo "compat: app sources changed; rebuilding app klib" >&2
    build_app_klib || return 2
    app_mod="app=$COMPAT_DIR/app.klib"
  fi
  t1=$(now_ms)
  classifier_args
  python3 "$CLASSIFIER" check "${CLS_ARGS[@]}" --prior-ms "app=$((t1 - t0))" \
    --module "ui=$BUILD/ui/ui.klib" --module "$app_mod"
}

commit_compat() {
  [ -f "$COMPAT_DIR/ui.new.json" ] && mv "$COMPAT_DIR/ui.new.json" "$COMPAT_DIR/ui.json"
  [ -f "$COMPAT_DIR/app.new.json" ] && mv "$COMPAT_DIR/app.new.json" "$COMPAT_DIR/app.json"
  touch "$COMPAT_DIR/app.stamp"
  return 0
}

# The classifier runs in the background while the cache-only compile runs
# (they use different daemons); `reload` waits for its verdict right before
# sending. Output and exit code go to files so the order of messages is fixed.
compat_start() {
  mkdir -p "$COMPAT_DIR"
  COMPAT_OUT=$COMPAT_DIR/check.out
  COMPAT_RCF=$COMPAT_DIR/check.rc
  rm -f "$COMPAT_RCF" "$COMPAT_DIR/ui.new.json" "$COMPAT_DIR/app.new.json"
  ( rc=0; compat_check > "$COMPAT_OUT" 2>&1 || rc=$?; echo "$rc" > "$COMPAT_RCF" ) &
  COMPAT_PID=$!
}

# Waits for the classifier; returns its exit code (0, 3, or 2).
compat_wait() {
  local tw0 tw1 rc
  tw0=$(now_ms)
  wait "$COMPAT_PID" 2>/dev/null || true
  COMPAT_PID=
  tw1=$(now_ms)
  cat "$COMPAT_OUT"
  echo "compat: wait ms=$((tw1 - tw0))"
  rc=$(cat "$COMPAT_RCF" 2>/dev/null || echo 2)
  return "$rc"
}

# Serialized state the reference app keeps for warm restarts.
read_saved_state() {
  PAGE=0; COUNT=0
  local data
  data=$(xcrun simctl get_app_container "$UDID" "$BUNDLE" data 2>/dev/null || true)
  if [ -n "$data" ] && [ -f "$data/Documents/hr-state.json" ]; then
    eval "$(python3 -c '
import json, sys
try:
    d = json.load(open(sys.argv[1]))
    print("PAGE=%d" % int(d.get("page", 0)))
    print("COUNT=%d" % int(d.get("count", 0)))
except Exception:
    print("PAGE=0"); print("COUNT=0")
' "$data/Documents/hr-state.json")"
  fi
}

case "${1:-}" in
ui-klib)
  mkdir -p "$BUILD/ui"
  rm -rf "$BUILD/ui/ui.klib"
  KLIB_SOCK=$HRC_KLIB_SOCKET
  ui_source_args
  ARGS=(-target ios_simulator_arm64 -p library -g
    -Xplugin="$PLUGIN" ${COMPOSE_FLAGS[@]+"${COMPOSE_FLAGS[@]}"} -opt-in=kotlinx.cinterop.ExperimentalForeignApi
    "${KLIBS[@]}" "${UI_SRC_ARGS[@]}" -o "$BUILD/ui/ui")
  if [ -S "$KLIB_SOCK" ]; then
    echo "ui-klib: daemon $KLIB_SOCK"
    # A compile error, or a stale socket after the daemon died: rerun with
    # kotlinc-native, which prints the compiler's messages.
    if ! python3 "$ROOT/tools/hrc.py" "$KLIB_SOCK" "${ARGS[@]}" >/dev/null; then
      echo "ui-klib: daemon compile failed; rerunning with kotlinc-native" >&2
      "$KC" "${ARGS[@]}"
    fi
  else
    echo "ui-klib: kotlinc-native"
    "$KC" "${ARGS[@]}"
  fi
  # What the klib was built from (source list or fragment arguments): package
  # rebuilds when this changes, e.g. when switching to or from UI_FRAGMENTS.
  printf '%s\n' "${UI_SRC_ARGS[@]}" > "$BUILD/ui/ui.inputs"
  echo "ui klib: $BUILD/ui/ui.klib"
  ;;

package)
  T_PKG0=$(now_ms); T_LAST=$T_PKG0; PKG_STEPS=""
  pkg_step() { local t; t=$(now_ms); PKG_STEPS="$PKG_STEPS $1=$((t - T_LAST))"; T_LAST=$t; }
  # A stale ui.klib would build the app (and the compat baseline) from old UI
  # code. Gradle's packageApp depends on compileKlib; a manual run may not.
  ui_source_args
  if [ ! -f "$BUILD/ui/ui.klib" ] || \
     [ -n "$(find "$UI_SRC_DIR" -name '*.kt' -newer "$BUILD/ui/ui.klib" -print -quit 2>/dev/null)" ] || \
     ! printf '%s\n' "${UI_SRC_ARGS[@]}" | cmp -s - "$BUILD/ui/ui.inputs"; then
    echo "package: ui.klib stale; rebuilding"
    "$ROOT/hotreload.sh" ui-klib
  fi
  pkg_step ui_klib
  mkdir -p "$BUILD/app" "$AUTO_CACHE" "$IC_DIR"
  # package must do the real host link; the editor-path cache-only switch
  # from hotreload.env applies to reload only.
  unset KONAN_HOT_RELOAD_CACHE_ONLY
  [ -f "$BUILD/ui/ui.klib" ] || { echo "ui.klib missing; run ui-klib first" >&2; exit 1; }
  # libKaldo comes only from KALDO_DYLIB or the toolchain manifest; there is no
  # fallback location. The manifest hash must match the file (INTERFACE 3.3).
  MANIFEST=$KOTLIN_DIST/toolchain.json
  manifest_field() {
    [ -f "$MANIFEST" ] || return 0
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("llvm",{}).get(sys.argv[2],""))' "$MANIFEST" "$1"
  }
  KALDO_DYLIB=${KALDO_DYLIB:-$(manifest_field kaldoIossim)}
  [ -n "$KALDO_DYLIB" ] || { echo "package: set KALDO_DYLIB or generate $MANIFEST (llvm.kaldoIossim)" >&2; exit 1; }
  [ -f "$KALDO_DYLIB" ] || { echo "package: libKaldo not found: $KALDO_DYLIB" >&2; exit 1; }
  KALDO_SHA=$(shasum -a 256 "$KALDO_DYLIB" | cut -d' ' -f1)
  WANT_SHA=$(manifest_field kaldoIossimSha256)
  if [ -n "$WANT_SHA" ] && [ "$KALDO_SHA" != "$WANT_SHA" ]; then
    echo "package: libKaldo sha256 $KALDO_SHA != toolchain.json kaldoIossimSha256 $WANT_SHA" >&2
    echo "package: regenerate the manifest (scripts/gen-toolchain.sh) or point KALDO_DYLIB at the right build" >&2
    exit 1
  fi
  echo "kaldo: $KALDO_DYLIB sha256=${KALDO_SHA:0:12}${WANT_SHA:+ (matches manifest)}"
  app_source_args
  # #32: the compiler writes the bootstrap payload paths to a list instead of copying ~100 MB of payloads into the
  # base executable (merge-payloads reads them from disk). A compiler without the option ignores it, writes no list,
  # and merge-payloads falls back to the embedded payloads.
  PAYLOAD_LIST="$BUILD/app/payloads.txt"; rm -f "$PAYLOAD_LIST"
  PKG_ARGS=($(common_args) "${KLIBS[@]}" -l "$BUILD/ui/ui.klib" \
    -Xbinary=splitPayloadList="$PAYLOAD_LIST" \
    -o "$BUILD/app/ComposeHR-base.kexe" "${APP_SRC_ARGS[@]}")
  # The ordinary compile daemon (hr-klib.sock, never cache-only) saves the JVM
  # start and warm-up (~3 s). It cannot see per-call environment, so
  # KONAN_SPLIT_MERGED_BOOTSTRAP still goes to kotlinc-native; on a daemon
  # failure kotlinc-native reruns the compile and prints the full message.
  if [ -S "$HRC_KLIB_SOCKET" ] && [ -z "${KONAN_SPLIT_MERGED_BOOTSTRAP:-}" ]; then
    echo "package: compile via daemon $HRC_KLIB_SOCKET"
    if ! python3 "$ROOT/tools/hrc.py" "$HRC_KLIB_SOCKET" "${PKG_ARGS[@]}" >/dev/null; then
      echo "package: daemon compile failed; rerunning with kotlinc-native" >&2
      "$KC" "${PKG_ARGS[@]}"
    fi
  else
    "$KC" "${PKG_ARGS[@]}"
  fi
  pkg_step compile
  # #32: the classifier baseline needs only ui.klib and the app sources, so it runs beside merge/embed/bundle.
  BASELINE_LOG="$BUILD/app/baseline.out"
  compat_baseline > "$BASELINE_LOG" 2>&1 &
  BASELINE_PID=$!
  # merge-cache keeps the processed library archives between packages (they
  # do not change), which takes the merge from ~6 s to ~2 s.
  KALDO_MERGE_CACHE_DIR=${KALDO_MERGE_CACHE_DIR:-$BUILD/merge-cache} \
  KALDO_HOT_PACKAGES=${KALDO_HOT_PACKAGES:-expui} \
  KALDO_HOT_SYMBOLS_OUT="$BUILD/app/hot-symbols.txt" \
  KALDO_PAYLOAD_LIST=$([ -s "$PAYLOAD_LIST" ] && echo "$PAYLOAD_LIST") \
    python3 "$ROOT/tools/merge-payloads.py" "$BUILD/app/ComposeHR-base.kexe" "$BUILD/app/merged-bootstrap.o"
  echo "hot symbols: $(wc -l < "$BUILD/app/hot-symbols.txt" | tr -d ' ')"
  pkg_step merge
  python3 "$ROOT/tools/embed-merged.py" "$BUILD/app/ComposeHR-base.kexe" "$BUILD/app/merged-bootstrap.o" "$OUT.kexe"
  pkg_step embed
  APP="$OUT.app"
  mkdir -p "$APP/Frameworks"
  rm -f "$APP/ComposeHR"; cp -c "$OUT.kexe" "$APP/ComposeHR" 2>/dev/null || cp "$OUT.kexe" "$APP/ComposeHR"   # clonefile on APFS
  cp "$ROOT/app/Info.plist" "$APP/Info.plist"
  cp "$KALDO_DYLIB" "$APP/Frameworks/libKaldo.iossim.dylib"
  # The linker leaves an absolute rpath to the LLVM install dir. That dylib is
  # outside the .app, and the simulator rejects it (codesign invalid-page), so
  # drop absolute rpaths and run from @executable_path/Frameworks.
  while IFS= read -r rp; do
    case "$rp" in
      /?*) install_name_tool -delete_rpath "$rp" "$APP/ComposeHR" 2>/dev/null || true ;;
    esac
  done < <(otool -l "$APP/ComposeHR" | awk '/LC_RPATH/{f=1;next} f&&/path /{print $2; f=0}')
  codesign --force --deep --sign - "$APP" >/dev/null 2>&1 || true
  echo "package: $APP"
  pkg_step bundle
  BASELINE_RC=0; wait "$BASELINE_PID" || BASELINE_RC=$?
  cat "$BASELINE_LOG"
  [ "$BASELINE_RC" = 0 ] || { echo "package: classifier baseline failed (exit $BASELINE_RC)" >&2; exit "$BASELINE_RC"; }
  pkg_step baseline
  echo "PACKAGE ms=$(( $(now_ms) - T_PKG0 ))$PKG_STEPS"
  ;;

reload)
  if [ "${2:-}" = "--no-warm" ]; then NO_WARM=1; else NO_WARM=0; fi
  compat_start
  trap '[ -n "${COMPAT_PID:-}" ] && kill "$COMPAT_PID" 2>/dev/null; true' EXIT
  mkdir -p "$BUILD" "$AUTO_CACHE" "$IC_DIR"
  date +%s > "$STAMP_FILE"
  rm -f "$BUILD/caches.txt"
  app_source_args
  ARGS=($(common_args) "${KLIBS[@]}" -l "$BUILD/ui/ui.klib" \
    -Xdump-built-caches-to="$BUILD/caches.txt" \
    -o "$BUILD/app/ComposeHR-patch.kexe" "${APP_SRC_ARGS[@]}")
  if [ -S "$HRC_SOCKET" ]; then
    echo "compile: daemon $HRC_SOCKET"
    python3 "$ROOT/tools/hrc.py" "$HRC_SOCKET" "${ARGS[@]}" >/dev/null
  else
    echo "compile: no daemon at $HRC_SOCKET; using kotlinc-native (start daemon/daemon-start.sh to save ~3 s per reload)"
    KONAN_HOT_RELOAD_CACHE_ONLY=1 "$KC" "${ARGS[@]}" >/dev/null
  fi
  START=$(cat "$STAMP_FILE")
  rm -rf "$BUILD/patch" && mkdir -p "$BUILD/patch"
  APP_OBJS=(); UI_OBJS=()
  while IFS= read -r a; do
    case "$a" in *per-file-cache/*.a) ;; *) continue ;; esac
    [ "$(stat -f %m "$a")" -lt "$START" ] && { echo "skip stale: $a"; continue; }
    d="$BUILD/patch/$(basename "$a" .a)"; mkdir -p "$d"
    (cd "$d" && ar x "$a")
    # Classify by module, from the cache path: the user modules' caches live
    # under IC_DIR, the ui klib's in "<klib unique name>-per-file-cache", the
    # app (program) module's in "<hash>-per-file-cache", one per source file.
    # Anything else (a library cache) is never sent.
    for o in "$d"/*.o; do
      case "$a" in
        "$IC_DIR"/*/ui-per-file-cache/*) UI_OBJS+=("$o") ;;
        "$IC_DIR"/*-per-file-cache/*) APP_OBJS+=("$o") ;;
        *) echo "unclassified object: $o (from $a)" >&2; exit 1 ;;
      esac
    done
  done < "$BUILD/caches.txt"
  [ ${#APP_OBJS[@]} -gt 0 ] || [ ${#UI_OBJS[@]} -gt 0 ] || { echo "no module object rebuilt" >&2; exit 1; }
  OBJS=("${APP_OBJS[@]}" "${UI_OBJS[@]}")
  OUT_OBJ=$BUILD/patch/both.o
  if [ ${#OBJS[@]} -eq 1 ]; then OUT_OBJ=${OBJS[0]}; else ld -r "${OBJS[@]}" -o "$OUT_OBJ"; fi
  echo "changed objects: app=${#APP_OBJS[@]} ui=${#UI_OBJS[@]}"
  nm -g -U "$OUT_OBJ" | awk '$2!="U"{print $3}' | sort -u > "$BUILD/patch/merged.syms"
  check_module() {
    mod=$1; shift
    for o in "$@"; do nm -g -U "$o" | awk '$2!="U"{print $3}'; done | sort -u > "$BUILD/patch/$mod.syms"
    total=$(wc -l < "$BUILD/patch/$mod.syms" | tr -d ' ')
    kept=$(comm -12 "$BUILD/patch/$mod.syms" "$BUILD/patch/merged.syms" | wc -l | tr -d ' ')
    echo "symbols: $mod defined=$total kept_in_merged=$kept"
    [ "$total" -gt 0 ] && [ "$kept" -gt 0 ] || { echo "symbol check failed for $mod; not sending" >&2; exit 1; }
  }
  [ ${#APP_OBJS[@]} -gt 0 ] && check_module app "${APP_OBJS[@]}"
  [ ${#UI_OBJS[@]} -gt 0 ] && check_module ui "${UI_OBJS[@]}"
  rc=0; compat_wait || rc=$?
  if [ "$rc" -eq 3 ]; then
    if [ "$NO_WARM" = 1 ]; then
      echo "compat: warm restart required; patch not sent (--no-warm)" >&2
      exit 3
    fi
    echo "compat: warm restart required; rebuilding and relaunching" >&2
    exec "$ROOT/hotreload.sh" warm
  elif [ "$rc" -ne 0 ]; then
    echo "compat: classifier failed (rc=$rc); patch not sent" >&2
    exit "$rc"
  fi
  python3 "$ROOT/tools/reload.py" "$OUT_OBJ"
  commit_compat
  ;;

warm)
  # Warm restart: keep the serializable state (page, counter), rebuild the app
  # and relaunch it. The classifier calls this automatically from `reload`.
  read_saved_state
  echo "warm: restoring page=$PAGE count=$COUNT"
  # Stop the previous console runner first: otherwise it sees its app die and
  # relaunches the OLD build while this warm restart is packaging the new one.
  pkill -f "$ROOT/run.sh" 2>/dev/null || true
  xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
  START=$(python3 -c 'import time; print(int(time.time()*1000))')
  "$ROOT/hotreload.sh" package
  PKG_DONE=$(python3 -c 'import time; print(int(time.time()*1000))')
  WARM_LOG=${WARM_LOG:-$ROOT/launch.log}
  : > "$WARM_LOG"
  APP_ARGS="--hr-state=$PAGE,$COUNT" LAUNCH_LOG="$WARM_LOG" \
    nohup "$ROOT/run.sh" >"$BUILD/warm-run.out" 2>&1 &
  deadline=$((SECONDS + 90)); ok=0
  while [ "$SECONDS" -lt "$deadline" ]; do
    if grep -q "count=$COUNT page=$PAGE" "$WARM_LOG" 2>/dev/null && \
       grep -q '^EH OK' "$WARM_LOG" 2>/dev/null; then ok=1; break; fi
    sleep 0.3
  done
  END=$(python3 -c 'import time; print(int(time.time()*1000))')
  if [ "$ok" != 1 ]; then
    echo "warm: app did not come back with page=$PAGE count=$COUNT (log $WARM_LOG, run.sh output $BUILD/warm-run.out)" >&2
    exit 1
  fi
  echo "WARM OK ms=$((END - START)) ms_package=$((PKG_DONE - START)) ms_relaunch=$((END - PKG_DONE)) page=$PAGE count=$COUNT"
  ;;

*)
  echo "usage: $0 {ui-klib|package|reload [--no-warm]|warm}" >&2
  exit 2
  ;;
esac
