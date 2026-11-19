#!/usr/bin/env python3
"""Merge a split-scheme kexe's __kaldo_boot payloads into one guest object.

Route A: instead of letting Kaldo's JIT materialize each Compose library cache
archive (which hits JITLink/ObjC image-info issues), link the bootstrap object
and every cache archive into a single relocatable object with `ld -r`. The
result is embedded as the only payload; reloads still send per-file user objects.

Usage: merge-payloads.py <kexe> <out.o>
"""
import os
import shutil
import struct
import subprocess
import sys
import tempfile

LLVM_HOME = os.environ.get("KONAN_LLVM_HOME",
                           os.path.expanduser("~/.konan/dependencies/llvm-21-aarch64-macos-dev-97"))
LLVM_OBJCOPY = os.path.join(LLVM_HOME, "bin", "llvm-objcopy")
LLVM_NM = os.path.join(LLVM_HOME, "bin", "llvm-nm")

# Only known cross-cache duplicate in Compose 1.10.0: ui and ui-text both
# define this internal getter. The ui copy is made weak so `ld -r` keeps the
# ui-text definition (both are the same code).
DUP_SYMBOL = "_kfun:androidx.compose.ui.text.<get-DefaultCacheSize>#internal"
DUP_ARCHIVE_MARKER = "ui-cache.a"


def rename_section(path, old_sect, new_sect, old_seg, new_seg):
    """Rename a Mach-O section in place (llvm-objcopy cannot express the comma)."""
    d = bytearray(open(path, "rb").read())
    magic = struct.unpack_from("<I", d, 0)[0]
    if magic != 0xFEEDFACF:
        raise SystemExit(f"rename_section: unexpected magic {magic:#x}")
    ncmds = struct.unpack_from("<I", d, 16)[0]
    off = 32
    hits = 0
    for _ in range(ncmds):
        cmd, cmdsize = struct.unpack_from("<II", d, off)
        if cmd == 0x19:  # LC_SEGMENT_64
            nsects = struct.unpack_from("<I", d, off + 64)[0]
            sect = off + 72
            for _ in range(nsects):
                sectname = bytes(d[sect:sect + 16]).rstrip(b"\x00")
                segname = bytes(d[sect + 16:sect + 32]).rstrip(b"\x00")
                if sectname == old_sect and segname == old_seg:
                    d[sect:sect + 16] = new_sect.ljust(16, b"\x00")
                    d[sect + 16:sect + 32] = new_seg.ljust(16, b"\x00")
                    hits += 1
                sect += 80
        off += cmdsize
    if not hits:
        raise SystemExit(f"rename_section: {old_seg},{old_sect} not found")
    open(path, "wb").write(d)
    print(f"renamed {old_seg},{old_sect} -> {new_seg},{new_sect} ({hits})")


def run(cmd, **kw):
    return subprocess.run(cmd, check=True, **kw)


def dump_section(kexe, out):
    run([LLVM_OBJCOPY, "--dump-section", f"__TEXT,__kaldo_boot={out}", kexe])


def parse_manifest(blob):
    assert blob[:5] == b"KALD0", blob[:5]
    msize, bsize, n = struct.unpack_from("<QQI", blob, 5)
    off = 25
    entries = []
    for _ in range(n):
        kind = blob[off]
        o, s = struct.unpack_from("<QQ", blob, off + 1)
        off += 17
        entries.append((kind, o, s))
    base = 25 + 17 * n
    return entries, base


def weaken_macho_symbol(path, symbol):
    d = bytearray(open(path, "rb").read())
    magic, = struct.unpack_from("<I", d, 0)
    if magic != 0xFEEDFACF:
        return 0
    ncmds, = struct.unpack_from("<I", d, 16)
    off = 32
    symoff = nsyms = stroff = None
    for _ in range(ncmds):
        cmd, cmdsize = struct.unpack_from("<II", d, off)
        if cmd == 0x2:  # LC_SYMTAB
            symoff, nsyms, stroff = struct.unpack_from("<III", d, off + 8)
        off += cmdsize
    if symoff is None:
        return 0
    hit = 0
    for i in range(nsyms):
        base = symoff + i * 16
        n_strx, n_type, n_sect, n_desc, n_value = struct.unpack_from("<IBBHQ", d, base)
        if n_type & 0x01 == 0:  # not external
            continue
        end = d.index(b"\0", stroff + n_strx)
        name = d[stroff + n_strx:end].decode("utf-8", "replace")
        if name == symbol:
            struct.pack_into("<H", d, base + 6, n_desc | 0x0080)  # N_WEAK_DEF
            hit += 1
    if hit:
        open(path, "wb").write(d)
    return hit


# ObjC sources of CMPUIKitObjcUtils are force-loaded into the app cache archive.
# They must stay in the *host* only: the JIT does not run dyld's ObjC fixups for
# selector constants on those objects, so a guest copy sends raw ivar/methname
# strings as SELs (e.g. `-[CMPViewController viewWillAppear:]` -> "_lifecycleState").
# KALDO_GUEST_KEEP_OBJC=1 restores the old behaviour for comparison.
SKIP_OBJC_PREFIX = "CMP"
HOT_SYMBOLS_OUT = os.environ.get(
    "KALDO_HOT_SYMBOLS_OUT",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "hot-symbols.txt"))
# Extra user-module packages whose symbols must be redirectable (comma separated).
HOT_PACKAGES = [p for p in os.environ.get("KALDO_HOT_PACKAGES", "").split(",") if p]
skipped_objc = 0
host_symbols = None
HOST_KEXE = None


def host_symbol_table(kexe):
    """Public+private defined symbols of the host image, as a name set."""
    global host_symbols
    if host_symbols is None:
        out = subprocess.run(["nm", "-m", kexe], capture_output=True, text=True).stdout
        host_symbols = set()
        for line in out.splitlines():
            parts = line.split()
            if not parts or "external" not in line and "non-external" not in line:
                continue
            if "(undefined)" in line:
                continue
            host_symbols.add(parts[-1])
    return host_symbols


def member_objc_classes(path):
    out = subprocess.run(["nm", "-m", path], capture_output=True, text=True).stdout
    classes = set()
    for line in out.splitlines():
        parts = line.split()
        if ("(undefined)" not in line and len(parts) >= 2
                and parts[-1].startswith("_OBJC_CLASS_$_")):
            classes.add(parts[-1])
    return classes


def member_defined_symbols(path):
    out = subprocess.run(["nm", "-g", path], capture_output=True, text=True).stdout
    syms = []
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[-2] != "U":
            syms.append(parts[-1])
        elif len(parts) == 2 and parts[0] != "U":
            syms.append(parts[-1])
    return syms


def member_is_cxx_only(path):
    """C++/ObjC++ objects JIT'd into the guest never run their static initializers.

    Keep them in the host image (force-loaded) so glibc-style global ctors run at
    process start; the guest resolves their symbols from the host.
    """
    syms = member_defined_symbols(path)
    if not syms:
        return False
    if any(s.startswith("_kfun:") or s.startswith("_ktype:") for s in syms):
        return False
    return any(s.startswith("__Z") or s.startswith("_Z") for s in syms)


def objc_member_is_host_backed(member_path, host):
    classes = member_objc_classes(member_path)
    return bool(classes) and classes <= host


def collect_hot_symbols(members, out_path):
    """Record the Kotlin symbols of the user module (the object that defines `#main`).

    Only these need redirectable stubs: a reload replaces just the user module.
    Externalizing every Compose symbol (100k+) bloats ORC's dependence graph and
    occasionally crashes simplifyDepGroups.
    """
    names = set()
    hit = False
    for m in members:
        p = os.path.join(os.path.dirname(m), m) if not os.path.isabs(m) else m
        if not os.path.isfile(p):
            continue
        try:
            out = subprocess.run(["nm", "-g", p], capture_output=True, text=True).stdout
        except Exception:
            continue
        syms = [line.split()[-1] for line in out.splitlines() if len(line.split()) >= 2]
        is_user_module = any(s.startswith("_kfun:#main") or s.startswith("_kfun:main") for s in syms)
        if not is_user_module:
            for pkg in HOT_PACKAGES:
                if any(s.startswith("_kfun:" + pkg + ".") or s.startswith("_kfun:" + pkg + "#") for s in syms):
                    is_user_module = True
                    break
        if is_user_module:
            hit = True
            names.update(s for s in syms if s.startswith("_kfun:"))
    if not hit:
        return 0
    with open(out_path, "a") as f:
        for n in sorted(names):
            f.write(n + "\n")
    return len(names)


def dedup_archive(src, dst):
    global skipped_objc
    tmp = tempfile.mkdtemp(prefix="dedup-")
    try:
        subprocess.run(["ar", "x", src], cwd=tmp, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        members = sorted(m for m in os.listdir(tmp) if not m.startswith("__.SYMDEF"))
        if os.environ.get("KALDO_GUEST_KEEP_OBJC") != "1":
            kept = []
            for m in members:
                p = os.path.join(tmp, m)
                if (m.startswith(SKIP_OBJC_PREFIX) and m.endswith(".o")
                        and objc_member_is_host_backed(p, host_symbol_table(HOST_KEXE))):
                    skipped_objc += 1
                    print(f"guest: dropping ObjC member {m} (host copy is used)")
                elif (m.endswith(".o") and os.environ.get("KALDO_GUEST_DROP_CXX") == "1"
                        and member_is_cxx_only(p)):
                    skipped_objc += 1
                    print(f"guest: dropping C++ member {m} (host copy is used)")
                else:
                    kept.append(m)
            members = kept
        hot = collect_hot_symbols([os.path.join(tmp, m) for m in members],
                                  HOT_SYMBOLS_OUT)
        if hot:
            print(f"hot symbols: {hot} (from {os.path.basename(src)})")
        subprocess.run(["ar", "rcs", dst] + members, cwd=tmp, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    finally:
        shutil.rmtree(tmp)


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    global HOST_KEXE
    kexe, out = sys.argv[1], sys.argv[2]
    HOST_KEXE = kexe
    open(HOT_SYMBOLS_OUT, "w").close()
    work = tempfile.mkdtemp(prefix="kaldo-merge-")
    try:
        return _merge(kexe, out, work)
    finally:
        # Remove the ~600 MB work directory on every exit path (exceptions,
        # SystemExit). KALDO_MERGE_KEEP=1 keeps it for debugging.
        if os.environ.get("KALDO_MERGE_KEEP") == "1":
            print(f"kept work dir: {work}", file=sys.stderr)
        else:
            shutil.rmtree(work, ignore_errors=True)


def _merge(kexe, out, work):
    raw = os.path.join(work, "boot.bin")
    dump_section(kexe, raw)
    blob = open(raw, "rb").read()
    entries, base = parse_manifest(blob)
    payloads = []
    for i, (kind, o, s) in enumerate(entries):
        data = blob[base + o:base + o + s]
        ext = "o" if kind == 0 else "a"
        p = os.path.join(work, f"p{i:02d}.{ext}")
        open(p, "wb").write(data)
        payloads.append(p)
    print(f"payloads: {len(payloads)}, total {sum(os.path.getsize(p) for p in payloads)} bytes")

    inputs = []
    # compiler-rt availability check object (real OS version semantics, not a stub).
    shim = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "compiler-rt-os_version_check.o")
    if os.path.exists(shim):
        inputs.append(shim)
        print(f"shim: {shim}")
    weakened = 0
    for p in payloads:
        if p.endswith(".a"):
            dedup = os.path.join(work, "d-" + os.path.basename(p))
            dedup_archive(p, dedup)
            # Patch the duplicate in the ui cache's member object.
            members = subprocess.check_output(["ar", "t", dedup]).decode("utf-8", "replace")
            if any(DUP_ARCHIVE_MARKER in m for m in members.splitlines()):
                tmp = tempfile.mkdtemp(prefix="weak-", dir=work)
                subprocess.run(["ar", "x", dedup], cwd=tmp, check=True)
                for m in os.listdir(tmp):
                    if m.endswith(".o"):
                        weakened += weaken_macho_symbol(os.path.join(tmp, m), DUP_SYMBOL)
                members = sorted(m for m in os.listdir(tmp) if not m.startswith("__.SYMDEF"))
                subprocess.run(["ar", "rcs", dedup] + members, cwd=tmp, check=True)
                shutil.rmtree(tmp)
            inputs.append(dedup)
        else:
            inputs.append(p)

    ld = subprocess.check_output(["xcrun", "--sdk", "iphonesimulator", "-f", "ld"]).decode().strip()
    sdk = subprocess.check_output(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-version"]).decode().strip()
    cmd = [ld, "-r", "-arch", "arm64",
           "-platform_version", "ios-simulator", "15.0", sdk,
           "-o", out, "-multiply_defined", "suppress"]
    for p in inputs:
        cmd += ["-force_load", p]
    print(f"weakened symbols: {weakened}")
    print("ld -r ...")
    run(cmd)
    # The compiler-rt object's symbols are private-external; globalize the
    # availability helpers so the JIT can resolve them by name.
    # ld -r demotes the ObjC export adapter tables (N_PEXT in the payloads) to
    # local symbols, so the JIT cannot resolve them by name and Kaldo reads the
    # zero-filled weak fallback instead. Globalize them back to private-external.
    for sym in ("___isPlatformVersionAtLeast", "___isOSVersionAtLeast",
                "_Kotlin_ObjCInterop_uniquePrefix",
                "_Kotlin_ObjCExport_sortedClassAdapters",
                "_Kotlin_ObjCExport_sortedClassAdaptersNum",
                "_Kotlin_ObjCExport_sortedProtocolAdapters",
                "_Kotlin_ObjCExport_sortedProtocolAdaptersNum",
                "_Kotlin_ObjCExport_sortedCategoryAdapters",
                "_Kotlin_ObjCExport_sortedCategoryAdaptersNum"):
        subprocess.run([LLVM_OBJCOPY, "--globalize-symbol", sym, out],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # JITLink fails on compact-unwind records whose DWARF FDE was dropped by
    # ld -r ("needs DWARF, but no FDE was found"). Remove the section; JITLink
    # then relies on __eh_frame (or no unwind info) instead.
    if os.environ.get("KALDO_KEEP_COMPACT_UNWIND") == "1":
        # Keep the records for CompactUnwindToEHFramePlugin, but hide them from
        # JITLink's own compact-unwind manager (it would require FDEs).
        rename_section(out, b"__compact_unwind", b"__kaldo_cu", b"__LD",
                       b"__KALDO")
    else:
        run([LLVM_OBJCOPY, "--remove-section", "__LD,__compact_unwind", out])
    print(f"merged: {out} ({os.path.getsize(out)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
