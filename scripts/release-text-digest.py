#!/usr/bin/env python3
"""Digest of a Mach-O arm64 binary's __TEXT,__text that does not depend on GOT slot order.

  release-text-digest.py <binary> [<binary> ...] [--explain]

Why: Apple's linker can emit two __DATA_CONST,__got entries for the same import
(seen for libobjc `_objc_msgSend`: one used by the selector stubs it synthesizes in
__objc_stubs, one used by ordinary code) and their order differs from link to link.
Every `adrp`+`ldr` that loads that import then encodes a different page offset, so
`__text` of two identical builds can differ by a few dozen bytes while dyld binds both
slots to the same address (REPORT sec.28).

How: every 64-bit `ldr Xt, [Xn, #imm]` whose address (from the preceding `adrp Xn`)
falls inside __got has its immediate, and the matching `adrp` immediate, set to zero;
the name the slot binds to (from `dyld_info -fixups`) is appended to a list in code
order. The digest is sha256(normalized __text + that list). A real code change, or a
load of a different symbol, still changes the digest.

Prints "<sha256>  <binary>  (N GOT loads normalized)"; exit 0, or 2 on a usage error.
"""
import hashlib
import struct
import subprocess
import sys

LC_SEGMENT_64 = 0x19


def sections(path):
    d = open(path, "rb").read()
    magic, = struct.unpack_from("<I", d, 0)
    if magic != 0xFEEDFACF:
        raise SystemExit(f"{path}: not a 64-bit Mach-O")
    ncmds, = struct.unpack_from("<I", d, 16)
    off, out = 32, {}
    for _ in range(ncmds):
        cmd, size = struct.unpack_from("<II", d, off)
        if cmd == LC_SEGMENT_64:
            nsects, = struct.unpack_from("<I", d, off + 64)
            s = off + 72
            for _ in range(nsects):
                sect = d[s:s + 16].rstrip(b"\0").decode()
                seg = d[s + 16:s + 32].rstrip(b"\0").decode()
                addr, sz, fo = struct.unpack_from("<QQI", d, s + 32)
                out[(seg, sect)] = (addr, sz, fo)
                s += 80
        off += size
    return d, out


def got_targets(path):
    """GOT slot address -> what it binds or rebases to, from dyld_info."""
    out = subprocess.run(["dyld_info", "-fixups", path], capture_output=True, text=True).stdout
    t = {}
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 5 and p[1] == "__got":
            t[int(p[2], 16)] = " ".join(p[3:])
    return t


def digest(path):
    d, secs = sections(path)
    taddr, tsize, toff = secs[("__TEXT", "__text")]
    got = secs.get(("__DATA_CONST", "__got"))
    text = bytearray(d[toff:toff + tsize])
    names = []
    if got:
        gaddr, gsize, _ = got
        targets = got_targets(path)
        last_adrp = {}  # register -> (index of the adrp word, page address)
        for i in range(0, len(text) - 3, 4):
            insn, = struct.unpack_from("<I", text, i)
            pc = taddr + i
            if insn & 0x9F000000 == 0x90000000:  # adrp
                rd = insn & 31
                imm = (((insn >> 5) & 0x7FFFF) << 2) | ((insn >> 29) & 3)
                if imm & (1 << 20):
                    imm -= 1 << 21
                last_adrp[rd] = (i, (pc & ~0xFFF) + (imm << 12))
                continue
            if insn & 0xFFC00000 == 0xF9400000:  # ldr Xt, [Xn, #imm12*8]
                rn = (insn >> 5) & 31
                if rn in last_adrp and i - last_adrp[rn][0] <= 64:
                    ai, page = last_adrp[rn]
                    addr = page + ((insn >> 10) & 0xFFF) * 8
                    if gaddr <= addr < gaddr + gsize:
                        struct.pack_into("<I", text, i, insn & ~(0xFFF << 10))
                        a, = struct.unpack_from("<I", text, ai)
                        struct.pack_into("<I", text, ai, a & 0x9F00001F)
                        names.append(targets.get(addr, f"unbound@{addr - gaddr:#x}"))
    h = hashlib.sha256(bytes(text))
    h.update("\n".join(names).encode())
    return h.hexdigest(), len(names)


def main(argv):
    paths = [a for a in argv if not a.startswith("-")]
    if not paths or any(a not in ("--explain",) for a in argv if a.startswith("-")):
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    for p in paths:
        h, n = digest(p)
        print(f"{h}  {p}  ({n} GOT loads normalized)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
