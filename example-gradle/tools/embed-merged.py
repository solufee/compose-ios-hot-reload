#!/usr/bin/env python3
"""Replace a split kexe's __kaldo_boot with a manifest for one or more merged objects (one entry each)."""
import os
import shutil
import struct
import subprocess
import sys
import tempfile

LLVM_HOME = os.environ.get("KONAN_LLVM_HOME",
                           os.path.expanduser("~/.konan/dependencies/llvm-21-aarch64-macos-dev-97"))
OBJCOPY = os.path.join(LLVM_HOME, "bin", "llvm-objcopy")

def main():
    # embed-merged.py <base kexe> <object> [<object> ...] <out kexe>: one manifest entry per object, in this order
    # (#32 step 2: the user object and the cached library object).
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    # "@file:<path>": the entry holds only the absolute path; libKaldo loads the object from that file at start
    # (kind 2, toolchain .10 on), so a large unchanged object stays out of the executable.
    base, objs, out = sys.argv[1], sys.argv[2:-1], sys.argv[-1]
    entries = []
    for o in objs:
        if o.startswith("@file:"):
            path = os.path.abspath(o[len("@file:"):])
            if not os.path.isfile(path):
                print(f"embed-merged: no such payload file: {path}", file=sys.stderr)
                return 1
            entries.append((2, path.encode()))
        else:
            entries.append((0, open(o, "rb").read()))
    blobs = [b for _, b in entries]
    bundle = sum(len(b) for b in blobs)
    msize = 25 + 17 * len(blobs) + bundle
    header = b"KALD0" + struct.pack("<QQI", msize, bundle, len(blobs))
    offset = 0
    for kind, b in entries:
        header += bytes([kind]) + struct.pack("<QQ", offset, len(b))
        offset += len(b)
    tmp = tempfile.mktemp(suffix=".bin")
    with open(tmp, "wb") as f:
        f.write(header)
        for b in blobs:
            f.write(b)
    # objcopy writes the output itself; no separate copy of the (large) base executable first (#32).
    subprocess.run([OBJCOPY, "--update-section",
                    f"__TEXT,__kaldo_boot={tmp}", base, out], check=True)
    os.unlink(tmp)
    print(f"embedded {' + '.join(objs)} -> {out} ({os.path.getsize(out)} bytes)")
    return 0

if __name__ == "__main__":
    sys.exit(main())
