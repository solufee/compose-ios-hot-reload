#!/bin/bash
# Build the gate inputs from a packaged example (build/app): the library object linked as a bundle against the host executable
# (two-level namespace: host symbols bound to the main executable, system symbols to their frameworks), and the injected gate.
# Usage: build.sh <out-dir>     (run from anywhere; uses example-gradle/build/app)
set -eu
O=$1; mkdir -p "$O"
HERE=$(cd "$(dirname "$0")" && pwd); B=$(cd "$HERE/../../../build/app" && pwd)
SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
PV="-platform_version ios-simulator 16.0 27.2"
FW="-framework UIKit -framework Foundation -framework CoreFoundation -framework CoreGraphics -framework CoreText -framework QuartzCore -framework Metal -framework ImageIO -framework CoreServices -framework UniformTypeIdentifiers -framework Security -framework SystemConfiguration -framework AVFoundation -lobjc -lc++ -lz -liconv -lSystem $(xcrun clang -print-resource-dir)/lib/darwin/libclang_rt.iossim.a"
/usr/bin/time -p xcrun ld -bundle -arch arm64 $PV -syslibroot "$SDK" -bundle_loader "$B/ComposeHR-base.kexe" \
  -o "$O/libs.bundle.dylib" "$B/merged-bootstrap.libs.o" $FW -undefined dynamic_lookup 2> "$O/ld-bundle.err" || { cat "$O/ld-bundle.err"; exit 1; }
grep real "$O/ld-bundle.err" | sed 's/^/ld -bundle /'
xcrun clang -arch arm64 -target arm64-apple-ios16.0-simulator -isysroot "$SDK" -dynamiclib -O2 -o "$O/gate.dylib" "$HERE/gate.c"
ls -l "$O/libs.bundle.dylib" "$O/gate.dylib" | awk '{print $5, $NF}'
echo "flat-namespace (dynamic_lookup) symbols left: $(xcrun dyld_info -fixups "$O/libs.bundle.dylib" 2>/dev/null | grep -c 'flat-namespace' || true)"
