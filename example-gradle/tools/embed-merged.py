#!/usr/bin/env python3
"""Replace a split kexe's __kaldo_boot with a single-entry manifest for a merged object."""
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
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    base, obj, out = sys.argv[1:]
    data = open(obj, "rb").read()
    msize = 25 + 17 + len(data)
    manifest = (b"KALD0" + struct.pack("<QQI", msize, len(data), 1) +
                bytes([0]) + struct.pack("<QQ", 0, len(data)) + data)
    tmp = tempfile.mktemp(suffix=".bin")
    open(tmp, "wb").write(manifest)
    # objcopy writes the output itself; no separate copy of the (large) base executable first (#32).
    subprocess.run([OBJCOPY, "--update-section",
                    f"__TEXT,__kaldo_boot={tmp}", base, out], check=True)
    os.unlink(tmp)
    print(f"embedded {obj} -> {out} ({os.path.getsize(out)} bytes)")
    return 0

if __name__ == "__main__":
    sys.exit(main())
