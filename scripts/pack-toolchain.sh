#!/bin/bash
# Build local, redistributable toolchain bundles (no publishing).
# Produces one .tar.zst per component plus packaging-manifest.json (schema 3).
# Publishing is a separate step (scripts/publish-toolchain.sh).
#
# Required:
#   KOTLIN_DIST          built Kotlin/Native dist
#   COMPOSE_KLIB_CACHE   dir containing the Compose fork klib cache (one dir per module)
#   COMPOSE_PLUGIN_JAR   Compose compiler plugin (shadow) jar
#   KALDO_IOSSIM         libKaldo.iossim.dylib
# Optional:
#   KALDO_MACOS          libKaldo.dylib (host)
#   ORC_RUNTIME          liborc_rt_iossim_arm64.a (needed at launch)
#   UIKIT_UTILS          libCMPUIKitObjcUtils.a (force-loaded into the app)
#   LLVM_TOOLS_FROM      dir whose bin/ has llvm-objcopy and llvm-nm (use a build
#                        that links only system libraries, e.g. the official
#                        JetBrains llvm-21-aarch64-macos-dev-97)
#   EXTERNAL_KLIBS       gen-external-klibs.py output; its bundledKlibs become a component
#   OUT_DIR              output dir (default: <repo>/../toolchain-dist)
#   TOOLCHAIN_VERSION    version string (default: 2.5.255-hotreload.1)
#
# The Kotlin bundle is the minimal subset needed to build and link an
# ios_simulator_arm64 app: bin/, konan/{lib,nativelib,targets,platformDef,
# konan.properties}, klib/common, klib/platform/ios_simulator_arm64, tools/.
# klib/cache is generated on demand and is not shipped.
# konan.properties is shipped with placeholders (<YOUR_LLVM_INSTALL>,
# <KALDO_INSTALL>); scripts/fetch-toolchain.py fills them in after download.
# Any text file that still contains the user's home directory or a macOS user-home path aborts packing.
set -e
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
KOTLIN_DIST=${KOTLIN_DIST:?KOTLIN_DIST is required}
COMPOSE_KLIB_CACHE=${COMPOSE_KLIB_CACHE:?COMPOSE_KLIB_CACHE is required}
COMPOSE_PLUGIN_JAR=${COMPOSE_PLUGIN_JAR:?COMPOSE_PLUGIN_JAR is required}
KALDO_IOSSIM=${KALDO_IOSSIM:?KALDO_IOSSIM is required}
KALDO_MACOS=${KALDO_MACOS:-}
ORC_RUNTIME=${ORC_RUNTIME:-}
UIKIT_UTILS=${UIKIT_UTILS:-}
LLVM_TOOLS_FROM=${LLVM_TOOLS_FROM:-}
OUT_DIR=${OUT_DIR:-$REPO_ROOT/../toolchain-dist}
TOOLCHAIN_VERSION=${TOOLCHAIN_VERSION:-2.5.255-hotreload.1}
ZSTD_LEVEL=${ZSTD_LEVEL:-19}

# Every component carries the project's LICENSE and NOTICE (in a folder with a
# unique name, so it never clashes with license files of the packed software).
REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
for f in LICENSE NOTICE; do
  [ -f "$REPO_ROOT/$f" ] || { echo "pack: $REPO_ROOT/$f is missing" >&2; exit 1; }
done

mkdir -p "$OUT_DIR"
STAGE=$(mktemp -d "${TMPDIR:-/tmp}/hr-pack.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT
LIST=$STAGE/.components.tsv   # id  file  extractDir  rawKB  channel
: > "$LIST"

pack() { # pack <stage-dir> <out-file>
  local src=$1 out=$2
  tar -C "$src" -cf - . | zstd -q -f -"$ZSTD_LEVEL" -T0 -o "$out"
}

leak_check() { # leak_check <stage-dir>: no local home paths in text files
  local hits
  hits=$(grep -rIl -e "$HOME" -e "/Us""ers/" "$1" 2>/dev/null || true)  # export-public: allow-home
  if [ -n "$hits" ]; then
    echo "pack: local paths found in text files of $(basename "$1"):" >&2
    echo "$hits" | sed "s|$STAGE/||" >&2
    exit 1
  fi
}

# Binary gate: every staged file, binaries and zip members included, is scanned
# for home paths, this machine's home dir, agent ids and the binary-scan entries
# of the local deny file (names, host names, internal names). Any hit stops packing.
binary_gate() { # binary_gate <stage-dir>
  if ! python3 "$REPO_ROOT/scripts/scan-binary-leaks.py" "$1" > "$STAGE/.scan.out" 2>&1; then
    echo "pack: $(basename "$1"): binary scan found local data (nothing packed):" >&2
    grep -E "^LEAK|^scan:" "$STAGE/.scan.out" | head -20 >&2
    exit 1
  fi
  grep -E "^exempt|^scan:" "$STAGE/.scan.out" | sed "s|^|  $(basename "$1"): |"
}

component() { # component <id> <stage-subdir> <extractDir> <channel> [file-suffix]
  local id=$1 dir=$2 extract=$3 channel=$4 suffix=${5:-}
  mkdir -p "$STAGE/$dir/licenses-compose-ios-hot-reload"
  cp "$REPO_ROOT/LICENSE" "$REPO_ROOT/NOTICE" "$STAGE/$dir/licenses-compose-ios-hot-reload/"
  leak_check "$STAGE/$dir"
  binary_gate "$STAGE/$dir"
  local raw out
  raw=$(du -sk "$STAGE/$dir" | cut -f1)
  out=$OUT_DIR/$id-$TOOLCHAIN_VERSION$suffix.tar.zst
  pack "$STAGE/$dir" "$out"
  rm -rf "${STAGE:?}/$dir"
  printf '%s\t%s\t%s\t%s\t%s\n' "$id" "$(basename "$out")" "$extract" "$raw" "$channel" >> "$LIST"
}

stage_kotlin() {
  local d=$1
  mkdir -p "$d/bin" "$d/konan" "$d/klib/platform"
  cp -R "$KOTLIN_DIST/bin/." "$d/bin/"
  cp -R "$KOTLIN_DIST/tools" "$d/tools"
  local sub
  for sub in lib nativelib targets platformDef; do
    cp -R "$KOTLIN_DIST/konan/$sub" "$d/konan/$sub"
  done
  cp "$KOTLIN_DIST/konan/konan.properties" "$d/konan/"
  # Local install paths become the same placeholders as in the Kotlin patch.
  sed -E -i '' \
    -e 's|^llvmHome\.macos_arm64 = .*|llvmHome.macos_arm64 = <YOUR_LLVM_INSTALL>|' \
    -e 's|^kaldoLinkerFlags\.macos_arm64 = .*|kaldoLinkerFlags.macos_arm64 = -L<KALDO_INSTALL>/lib -rpath <KALDO_INSTALL>/lib -lKaldo|' \
    -e 's|^kaldoLinkerFlags\.ios_simulator_arm64 = .*|kaldoLinkerFlags.ios_simulator_arm64 = -L<KALDO_INSTALL>/lib -lKaldo.iossim|' \
    "$d/konan/konan.properties"
  [ -f "$KOTLIN_DIST/konan/compiler.fingerprint" ] && \
    cp "$KOTLIN_DIST/konan/compiler.fingerprint" "$d/konan/"
  cp -R "$KOTLIN_DIST/klib/common" "$d/klib/common"
  cp -R "$KOTLIN_DIST/klib/platform/ios_simulator_arm64" "$d/klib/platform/"
}

echo "staging Kotlin/Native subset..."
stage_kotlin "$STAGE/kotlin-native"
component kotlin-native kotlin-native kotlin-native release -macos-aarch64

echo "staging Compose klibs..."
cp -R "$COMPOSE_KLIB_CACHE" "$STAGE/compose-klibs"
component compose-klibs compose-klibs compose-klibs release

echo "staging Compose compiler plugin..."
mkdir -p "$STAGE/compose-plugin"
cp "$COMPOSE_PLUGIN_JAR" "$STAGE/compose-plugin/"
PLUGIN_JAR_NAME=$(basename "$COMPOSE_PLUGIN_JAR")
component compose-compiler-plugin compose-plugin compose-plugin maven

echo "staging libKaldo..."
# Both libKaldo components extract into the same kaldo/lib directory, which is
# what konan.properties' kaldoLinkerFlags (-L<KALDO_INSTALL>/lib) expects.
KALDO_IOSSIM_SHA=$(shasum -a 256 "$KALDO_IOSSIM" | cut -d' ' -f1)
mkdir -p "$STAGE/kaldo-iossim/lib"
cp "$KALDO_IOSSIM" "$STAGE/kaldo-iossim/lib/libKaldo.iossim.dylib"
component kaldo-iossim kaldo-iossim kaldo release
if [ -n "$KALDO_MACOS" ]; then
  mkdir -p "$STAGE/kaldo-macos/lib"
  cp "$KALDO_MACOS" "$STAGE/kaldo-macos/lib/libKaldo.dylib"
  component kaldo-macos kaldo-macos kaldo release
fi

ORC_NAME=""
if [ -n "$ORC_RUNTIME" ]; then
  echo "staging ORC runtime..."
  mkdir -p "$STAGE/orc-runtime"
  cp "$ORC_RUNTIME" "$STAGE/orc-runtime/"
  ORC_NAME=$(basename "$ORC_RUNTIME")
  component orc-runtime orc-runtime orc-runtime release
fi

UIKIT_NAME=""
if [ -n "$UIKIT_UTILS" ]; then
  echo "staging CMP UIKit utils..."
  mkdir -p "$STAGE/cmp-uikit-utils"
  cp "$UIKIT_UTILS" "$STAGE/cmp-uikit-utils/"
  UIKIT_NAME=$(basename "$UIKIT_UTILS")
  component cmp-uikit-utils cmp-uikit-utils cmp-uikit-utils release
fi

if [ -n "$LLVM_TOOLS_FROM" ]; then
  echo "staging llvm-objcopy / llvm-nm..."
  mkdir -p "$STAGE/llvm-tools/bin"
  for t in llvm-objcopy llvm-nm; do
    cp "$LLVM_TOOLS_FROM/bin/$t" "$STAGE/llvm-tools/bin/"
    # Only system libraries are allowed, so the tools run on any macOS host.
    if otool -L "$STAGE/llvm-tools/bin/$t" | tail -n +2 | grep -v -e '^\s*/usr/lib/' -e '^\s*/System/' | grep -q .; then
      echo "pack: $t links non-system libraries; use a self-contained build for LLVM_TOOLS_FROM" >&2
      exit 1
    fi
  done
  component llvm-tools llvm-tools llvm-tools release
fi

# Public klibs that no public Maven repository serves (gen-external-klibs.py
# "bundledKlibs") are packed as one more component; the rest are downloaded by
# fetch-toolchain.py from their repositories (manifest "externalKlibs").
if [ -n "${EXTERNAL_KLIBS:-}" ]; then
  n=$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1])).get("bundledKlibs", [])))' "$EXTERNAL_KLIBS")
  if [ "$n" -gt 0 ]; then
    echo "staging $n bundled klibs..."
    python3 - "$EXTERNAL_KLIBS" "$STAGE" <<'PY2'
import hashlib, json, os, shutil, sys
spec, stage = json.load(open(sys.argv[1])), sys.argv[2]
for k in spec["bundledKlibs"]:
    dest = os.path.join(stage, k["path"])
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    shutil.copyfile(k["source"], dest)
    if hashlib.sha256(open(dest, "rb").read()).hexdigest() != k["sha256"]:
        sys.exit(f"pack: {k['source']} does not match its recorded sha256")
PY2
    # Licenses of the bundled binaries (skiko, Skia and what Skia links statically).
    if [ -d "$REPO_ROOT/licenses/bundled-klibs" ]; then
      cp -R "$REPO_ROOT/licenses/bundled-klibs" "$STAGE/bundled-klibs/licenses-bundled-klibs"
    else
      echo "pack: $REPO_ROOT/licenses/bundled-klibs is missing (licenses of the bundled klibs)" >&2; exit 1
    fi
    component bundled-klibs bundled-klibs bundled-klibs release
  fi
fi

python3 - "$OUT_DIR" "$TOOLCHAIN_VERSION" "$LIST" "$PLUGIN_JAR_NAME" \
  "$KALDO_IOSSIM_SHA" "$ORC_NAME" "$UIKIT_NAME" <<'PY'
import hashlib, json, os, sys, datetime
out, ver, listfile, plugin_jar, kaldo_sha, orc_name, uikit_name = sys.argv[1:]
components = []
for line in open(listfile):
    cid, fname, extract, raw_kb, channel = line.rstrip("\n").split("\t")
    path = os.path.join(out, fname)
    components.append({
        "id": cid,
        "file": fname,
        "extractDir": extract,
        "rawBytes": int(raw_kb) * 1024,
        "bytes": os.path.getsize(path),
        "sha256": hashlib.sha256(open(path, "rb").read()).hexdigest(),
        "channel": channel,
    })
ids = {c["id"] for c in components}
layout = {
    "kotlinDist": "kotlin-native",
    "composePluginJar": f"compose-plugin/{plugin_jar}",
    "composeKlibDir": "compose-klibs",
    "kaldoHome": "kaldo",
    "kaldoIossim": "kaldo/lib/libKaldo.iossim.dylib",
}
if "llvm-tools" in ids:
    layout["llvmTools"] = "llvm-tools"
if orc_name:
    layout["orcRuntime"] = f"orc-runtime/{orc_name}"
if uikit_name:
    layout["uikitUtils"] = f"cmp-uikit-utils/{uikit_name}"
doc = {
    "schema": 3,
    "toolchainVersion": ver,
    "generatedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
    "published": False,
    "layout": layout,
    "kaldoIossimSha256": kaldo_sha,
    "components": components,
}
outpath = os.path.join(out, "packaging-manifest.json")
with open(outpath, "w") as f:
    json.dump(doc, f, indent=2, ensure_ascii=False)
    f.write("\n")
total_raw = sum(c["rawBytes"] for c in components)
total_packed = sum(c["bytes"] for c in components)
print(f"manifest: {outpath}")
print(f"total raw: {total_raw/2**20:.1f} MiB, packed: {total_packed/2**20:.1f} MiB")
for c in components:
    print(f"  {c['id']:<26} raw {c['rawBytes']/2**20:8.1f} MiB  packed {c['bytes']/2**20:8.1f} MiB  {c['sha256'][:16]}...")
PY
