#!/bin/bash
# Start (or restart) the hot-reload Kotlin/Native compile daemons.
#
# Two daemons share one warm compiler jar:
#   hr-compile.sock  cache-only compiles for the editor path (KONAN_HOT_RELOAD_CACHE_ONLY=1)
#   hr-klib.sock     ordinary `-p library` compiles for the ui module (no cache-only)
#
# The socket directory is private (0700) and the sockets are 0600, because the
# daemons execute whatever compilation arguments they receive.
#
# Usage: daemon-start.sh [--force]
set -e
KOTLIN_DIST=${KOTLIN_DIST:?KOTLIN_DIST is required (see README)}
SRC=$(cd "$(dirname "$0")" && pwd)
WORKDIR=${HR_WORKDIR:-$PWD}
DIST=$KOTLIN_DIST
JAR=$DIST/konan/lib/kotlin-native-compiler-embeddable.jar
SOCKDIR=${HRC_SOCKET_DIR:-$WORKDIR/.hr}
SOCK=${HRC_SOCKET:-$SOCKDIR/hr-compile.sock}
KLIB_SOCK=${HRC_KLIB_SOCKET:-$SOCKDIR/hr-klib.sock}
LOG=${HRC_LOG:-$WORKDIR/hr-daemon.log}
KLIB_LOG=${HRC_KLIB_LOG:-$WORKDIR/hr-klib-daemon.log}
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

mkdir -p "$SOCKDIR"
chmod 700 "$SOCKDIR"

stop_own() {
  local sock=$1
  # Any compile daemon serving exactly this socket path (whatever compiler jar
  # it runs, so a toolchain switch replaces it). Socket paths are per project,
  # so another project's or line's daemon never matches.
  ps -axo pid=,args= | while read -r pid args; do
    case "$args" in
      *"HrCompileDaemon "*" $sock") kill "$pid" 2>/dev/null || true ;;
    esac
  done
}

if [ "$FORCE" = 1 ]; then
  stop_own "$SOCK"
  stop_own "$KLIB_SOCK"
  sleep 0.5
  rm -f "$SOCK" "$KLIB_SOCK"
fi

check_socket() {
  local sock=$1
  [ -S "$sock" ] || return 0
  local owner
  owner=$(stat -f '%Su' "$sock" 2>/dev/null || echo '?')
  if [ "$owner" != "$(whoami)" ]; then
    echo "refusing to start: $sock is owned by $owner" >&2
    exit 1
  fi
  if python3 - "$sock" <<'PY'
import socket,sys
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
try:
    s.connect(sys.argv[1]); sys.exit(0)
except OSError:
    sys.exit(1)
PY
  then
    echo "refusing to start: a daemon is already listening on $sock" >&2
    exit 1
  fi
  rm -f "$sock"
}

check_socket "$SOCK"
check_socket "$KLIB_SOCK"

CLASSES=${HRC_CLASSES:-$SOCKDIR/classes}
mkdir -p "$CLASSES"
chmod 700 "$CLASSES"
"$JAVA_HOME/bin/javac" -d "$CLASSES" "$SRC/HrCompileDaemon.java"

start_one() {
  local sock=$1 log=$2 cache_only=$3 xmx=$4
  ( umask 077; KONAN_HOT_RELOAD_CACHE_ONLY=$cache_only \
    nohup "$JAVA_HOME/bin/java" -ea -Xmx"$xmx" -XX:+UseSerialGC \
    -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -Dkonan.home="$DIST" \
    -cp "$JAR:$CLASSES" HrCompileDaemon "$JAR" "$sock" > "$log" 2>&1 & )
  for i in $(seq 1 100); do
    [ -S "$sock" ] && grep -q READY "$log" 2>/dev/null && break
    sleep 0.1
  done
  chmod 600 "$sock" 2>/dev/null || true
  grep -q READY "$log" && echo "daemon ready: $sock (log $log)" || { echo "daemon failed: $log"; tail -5 "$log"; exit 1; }
}

start_one "$SOCK" "$LOG" 1 3G
start_one "$KLIB_SOCK" "$KLIB_LOG" 0 1500m
