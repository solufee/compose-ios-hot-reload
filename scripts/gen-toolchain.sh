#!/bin/bash
# Generate toolchain.json for the Gradle plugin. All paths come from env vars;
# nothing here is machine-specific.
#
# Required:
#   KOTLIN_DIST          Kotlin/Native dist (contains bin/kotlinc-native)
#   COMPOSE_PLUGIN_JAR   Compose compiler plugin jar
#   KLIBLIST             klib list file (one path per line)
#   KALDO_DYLIB          libKaldo.iossim.dylib (e.g. <KALDO_INSTALL>/lib/...; no default)
# Optional:
#   TOOLCHAIN_JSON       output path (default: $KOTLIN_DIST/toolchain.json)
#   PATCHES_DIR          directory with the three patches (default: <repo>/patches)
#
# Toolchain identity is "public base commit + patch sha256", never a local branch.
# Re-run this script in the same commit whenever any file in patches/ changes or
# libKaldo is rebuilt; llvm.kaldoIossimSha256 binds the manifest to the binary.
# The dist used for local development was built from the work-7606 line
# (eb20031f740, plus two uncommitted edits that are already inside the Kotlin
# patch); that is a build source note, not the declared baseline.
set -e
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
KOTLIN_DIST=${KOTLIN_DIST:?KOTLIN_DIST is required}
COMPOSE_PLUGIN_JAR=${COMPOSE_PLUGIN_JAR:?COMPOSE_PLUGIN_JAR is required}
KLIBLIST=${KLIBLIST:?KLIBLIST is required}
KALDO_DYLIB=${KALDO_DYLIB:?KALDO_DYLIB is required (no default location)}
TOOLCHAIN_JSON=${TOOLCHAIN_JSON:-$KOTLIN_DIST/toolchain.json}
PATCHES_DIR=${PATCHES_DIR:-$REPO_ROOT/patches}

KOTLIN_VERSION=${KOTLIN_VERSION:-2.5.255-SNAPSHOT}
COMPOSE_VERSION=${COMPOSE_VERSION:-1.13.0-rc01}
KOTLIN_BASE=${KOTLIN_BASE:-8e53ee99fbc08e264828ab082aee3e448194eca8}
LLVM_BASE=${LLVM_BASE:-2f8844c2b35557baced9106c50b9b8758fa6a0de}
COMPOSE_BASE=${COMPOSE_BASE:-f39822e6a77eea5d194e4f9bb56cb6961321b849}
PATCH_KOTLIN=${PATCH_KOTLIN:-kotlin-native-hot-reload.patch}
PATCH_LLVM=${PATCH_LLVM:-llvm-kaldo.patch}
PATCH_COMPOSE=${PATCH_COMPOSE:-compose-multiplatform-core-build.patch}
XCODE_VERSION=${XCODE_VERSION:-$(xcodebuild -version 2>/dev/null | tr '\n' ' ' | sed 's/ *$//')}
IOS_RUNTIME=${IOS_RUNTIME:-$(xcrun simctl list runtimes 2>/dev/null | grep -oE 'iOS [0-9.]+' | head -1)}

sha256() {
  shasum -a 256 "$PATCHES_DIR/$1" | cut -d' ' -f1
}

# The manifest must describe the binary that is actually installed, not only
# the patch it should have been built from (interfaceVersion 3).
[ -f "$KALDO_DYLIB" ] || { echo "gen-toolchain: KALDO_DYLIB not found: $KALDO_DYLIB" >&2; exit 1; }
KALDO_SHA=$(shasum -a 256 "$KALDO_DYLIB" | cut -d' ' -f1)

python3 - "$KOTLIN_DIST" "$COMPOSE_PLUGIN_JAR" "$KLIBLIST" "$KALDO_DYLIB" "$KALDO_SHA" "$TOOLCHAIN_JSON" \
  "$KOTLIN_VERSION" "$COMPOSE_VERSION" "$KOTLIN_BASE" "$LLVM_BASE" "$COMPOSE_BASE" \
  "$XCODE_VERSION" "$IOS_RUNTIME" \
  "$PATCH_KOTLIN" "$(sha256 "$PATCH_KOTLIN")" \
  "$PATCH_LLVM" "$(sha256 "$PATCH_LLVM")" \
  "$PATCH_COMPOSE" "$(sha256 "$PATCH_COMPOSE")" <<'PY'
import json, os, sys, datetime
(kdist, plugin, kliblist, kaldo, kaldosha, out, kver, cver, kbase, lbase, cbase, xcode, ios,
 kpatch, kpatchsha, lpatch, lpatchsha, cpatch, cpatchsha) = sys.argv[1:]
doc = {
    "interfaceVersion": 8,
    "layout": "local",
    "generatedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
    "kotlin": {
        "version": kver,
        "dist": os.path.abspath(kdist),
        "kotlincNative": os.path.join(os.path.abspath(kdist), "bin", "kotlinc-native"),
        "compilerJar": os.path.join(os.path.abspath(kdist), "konan", "lib",
                                    "kotlin-native-compiler-embeddable.jar"),
        "klibDir": os.path.join(os.path.abspath(kdist), "klib"),
        "baseCommit": kbase,
        "patch": kpatch,
        "patchSha256": kpatchsha,
    },
    "compose": {
        "version": cver,
        "pluginJar": os.path.abspath(plugin),
        "klibList": os.path.abspath(kliblist),
        "baseCommit": cbase,
        "patch": cpatch,
        "patchSha256": cpatchsha,
    },
    "llvm": {
        "baseCommit": lbase,
        "kaldoIossim": os.path.abspath(kaldo),
        "kaldoIossimSha256": kaldosha,
        "patch": lpatch,
        "patchSha256": lpatchsha,
    },
    "host": {"xcode": xcode, "iosRuntime": ios, "arch": "arm64"},
    "runtime": {
        "target": "ios_simulator_arm64",
        "reloadPort": 5567,
        "reloadPortEnv": "KONAN_HOT_RELOAD_PORT",
        "singleObjectPerReload": True,
        "mergeChangedObjectsWith": "ld -r",
    },
}
# First-frame ready marker (toolchain .11, INTERFACE §6.1): declared only when the Compose ui klib in KLIBLIST really
# prints it, so a manifest regenerated for an older toolchain never claims a marker that its app will not print.
READY_MARKER = "HR READY first-frame"
def ui_klib_prints_marker():
    for line in open(kliblist).read().splitlines():
        p = line.strip().rstrip("/")
        if not p or p.startswith("#") or os.path.basename(p) != "ui" or not os.path.isdir(p):
            continue
        for root, _, files in os.walk(p):
            for f in files:
                with open(os.path.join(root, f), "rb") as fh:
                    if READY_MARKER.encode() in fh.read():
                        return True
    return False
if ui_klib_prints_marker():
    doc["runtime"]["readyMarker"] = READY_MARKER
with open(out, "w") as f:
    json.dump(doc, f, indent=2, ensure_ascii=False)
    f.write("\n")
print(out)
PY
