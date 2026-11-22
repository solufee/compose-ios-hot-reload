#!/usr/bin/env python3
"""Merge a split-scheme kexe's __kaldo_boot payloads into one guest object.

Route A: instead of letting Kaldo's JIT materialize each Compose library cache
archive (which hits JITLink/ObjC image-info issues), link the bootstrap object
and every cache archive into a single relocatable object with `ld -r`. The
result is embedded as the only payload; reloads still send per-file user objects.

Usage: merge-payloads.py <kexe> <out.o>
       merge-payloads.py --rename-compact-unwind <object>...   (hot reload patch objects, Issue #80)
       merge-payloads.py --patch-dups <out.dups.json> <hot-symbols.txt> <module>=<object>...   (patch objects, Issue #91)
"""
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile

LLVM_HOME = os.environ.get("KONAN_LLVM_HOME",
                           os.path.expanduser("~/.konan/dependencies/llvm-21-aarch64-macos-dev-97"))
LLVM_OBJCOPY = os.path.join(LLVM_HOME, "bin", "llvm-objcopy")
LLVM_NM = os.path.join(LLVM_HOME, "bin", "llvm-nm")

# Issue #91: exported symbols that the caches of two or more libraries define. A whole-program link deserializes library
# declarations from roots, so a signature is linked at most once (an unreferenced one not at all); per-file and library
# caches compile every declaration of their library, so `ld -r` sees one definition per library. Examples: a compiler
# plugin that emits the same declaration in every module downstream of the one it describes (Koin's hints), or two
# libraries that define the same internal declaration (Compose ui and ui-text: <get-DefaultCacheSize>#internal). Each such
# symbol gets one of three tiers (see plan_duplicates); there is no list of known symbols.
DUPS_RECORD_SUFFIX = ".dups.json"


# Optional (#32): a file listing the payload paths, written by the compiler with -Xbinary=splitPayloadList=<file>.
# When set, the payloads are read from those paths instead of the executable's __kaldo_boot section.
PAYLOAD_LIST = os.environ.get("KALDO_PAYLOAD_LIST", "")
# Optional (#32 step 2, needs KALDO_PAYLOAD_LIST and KALDO_MERGE_CACHE_DIR): colon-separated directories whose payloads
# are libraries. They are linked once into a cached "<out-stem>.libs.o" that is reused while the libraries do not change;
# each merge then links only the user payloads into <out>. Both objects are embedded (embed-merged.py base out-libs out).
LIB_PAYLOAD_DIRS = [d.rstrip("/") + "/" for d in os.environ.get("KALDO_LIB_PAYLOAD_DIRS", "").split(":") if d]


def rename_section(path, old_sect, new_sect, old_seg, new_seg, missing_ok=False):
    """Rename a Mach-O section in place (llvm-objcopy cannot express the comma).
    missing_ok: return False instead of exiting when the section is not there."""
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
        if missing_ok:
            return False
        raise SystemExit(f"rename_section: {old_seg},{old_sect} not found")
    open(path, "wb").write(d)
    print(f"renamed {old_seg},{old_sect} -> {new_seg},{new_sect} ({hits})")
    return True


def rename_compact_unwind(path):
    """Issue #80: a hot reload patch object (made with plain `ld -r`) keeps __LD,__compact_unwind; libKaldo synthesizes
    and registers FDEs only from __KALDO,__kaldo_cu, so without this an exception unwinding through patch code ends the
    app. Same rename as the bootstrap merge (KALDO_KEEP_COMPACT_UNWIND=1). Idempotent: an object that already has
    __kaldo_cu, or has no compact unwind, is left as it is."""
    return rename_section(path, b"__compact_unwind", b"__kaldo_cu", b"__LD", b"__KALDO", missing_ok=True)


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


# ---------------------------------------------------------------- Issue #91: one exported name, several libraries

_PER_FILE_CACHE = re.compile(r"/([^/]+)-per-file-cache/([^/]+)/")
_LIBRARY_CACHE = re.compile(r"/([^/]+)-cache/")
_NM_SYMBOL = re.compile(r"^\s*(?:[0-9a-f]+\s+)?\(([^)]*)\)\s+((?:weak |private )*(?:external|non-external))((?: \[[^\]]*\])*) (.+)$")


def library_of(src):
    """(library, cache) of a payload: <library>-per-file-cache/<file>/... or <uniqueName>-cache/... (else the file)."""
    m = _PER_FILE_CACHE.search(src)
    if m:
        return m.group(1), m.group(2)
    libs = _LIBRARY_CACHE.findall(src)  # the last one: the path also has e.g. ".../auto-cache/..."
    if libs:
        return libs[-1], os.path.basename(src)
    return src, os.path.basename(src)


def nm_table(path):
    """External symbols of an archive or object: {"def": {name: member}, "weak": [names], "undef": [names]}.

    `nm -m` (not -P) because only it tells a weak definition from a strong one; weak definitions never clash."""
    d, base = os.path.split(os.path.abspath(path))
    out = subprocess.run(["nm", "-m", "-A", base], cwd=d, capture_output=True, text=True, errors="replace").stdout
    defs, weak, undef = {}, set(), set()
    prefix = base + ":"
    for line in out.splitlines():
        if not line.startswith(prefix):
            continue
        rest = line[len(prefix):]
        member = ""
        if path.endswith(".a"):
            i = rest.find(": ")
            if i < 0:
                continue
            member, rest = rest[:i], rest[i + 2:]
        m = _NM_SYMBOL.match(rest)
        if not m:
            continue
        where, attrs, name = m.group(1), m.group(2), m.group(4)
        if attrs.endswith("non-external"):
            continue
        if where == "undefined":
            undef.add(name)
        elif attrs.startswith("weak"):
            weak.add(name)
        else:
            defs.setdefault(name, member)
    return {"def": defs, "weak": sorted(weak), "undef": sorted(undef)}


def cached_nm_table(path, key):
    """nm_table, kept in the merge cache next to the processed archive of the same key."""
    if not (MERGE_CACHE and key):
        return nm_table(path)
    f = os.path.join(MERGE_CACHE, key + ".syms.json")
    try:
        return json.load(open(f))
    except (OSError, ValueError):
        pass
    t = nm_table(path)
    tmp = f"{f}.{os.getpid()}.tmp"
    with open(tmp, "w") as out:
        json.dump(t, out)
    os.replace(tmp, f)
    return t


def extract_member(archive, member, work):
    """Path of [member] of [archive] (or the object itself), extracted once into the work directory."""
    if not archive.endswith(".a"):
        return archive
    d = os.path.join(work, "members", hashlib.sha256(archive.encode()).hexdigest()[:16])
    if not os.path.isdir(d):
        os.makedirs(d)
        subprocess.run(["ar", "x", archive], cwd=d, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return os.path.join(d, member)


_INSN = re.compile(r"^\s*([0-9a-f]+):\s+([0-9a-f]{8})\s")
_RELOC = re.compile(r"^\s+([0-9a-f]+):\s+(ARM64_RELOC_\w+)\s+(.+?)\s*$")
_START = re.compile(r"^([0-9a-f]+) <(.*)>:$")


def symbol_body(obj, name):
    """The code of [name] in [obj] for an equality test: instruction words and (offset, type, target) of each relocation,
    offsets relative to the symbol, so the same code at another address compares equal. None for a non-code symbol."""
    out = subprocess.run(["xcrun", "objdump", "-d", "-r", "--disassemble-symbols=" + name, obj],
                         capture_output=True, text=True, errors="replace").stdout
    start, words, relocs = None, [], []
    for line in out.splitlines():
        m = _START.match(line)
        if m:
            if start is not None:
                break  # the next symbol
            start = int(m.group(1), 16)
            continue
        if start is None:
            continue
        m = _RELOC.match(line)
        if m:
            relocs.append((int(m.group(1), 16) - start, m.group(2), m.group(3)))
            continue
        m = _INSN.match(line)
        if m:
            words.append(m.group(2))
    if not words:
        return None
    return tuple(words), tuple(relocs)


def body_digest(body):
    return hashlib.sha256(repr(body).encode()).hexdigest()[:16]


def plan_duplicates(copies_by_name, refs, hot, work):
    """Tiers for the names that several libraries define strongly. copies_by_name: {name: [(input, member, library,
    cache)]}; refs: names some input object or the host image leaves undefined; hot: the hot symbol names.

      1 local    nothing references the name and it is not hot: every copy becomes a local symbol of its own object
                 (nothing dropped, nothing merged; the name is not linked, as in the whole-program link, where an
                 unreferenced declaration is never deserialized);
      2 weak     referenced, not hot, and all copies byte-identical (instruction words + relocation targets, offsets
                 relative to the symbol): the first copy stays strong, the others become weak, so `ld -r` keeps one;
      3 conflict referenced or hot, and the copies differ or cannot be compared: a semantic conflict that needs a human
                 decision; nothing is picked by link order.
    Two copies in ONE library (two files of a module) are distinct declarations that share a name: 'same_library'."""
    plan = {"local": {}, "weak": {}, "conflict": {}, "same_library": {}}
    for name in sorted(copies_by_name):
        copies = copies_by_name[name]
        libs = [c[2] for c in copies]
        if len(set(libs)) < len(libs):
            plan["same_library"][name] = (copies, None)
        elif name not in refs and name not in hot:
            plan["local"][name] = (copies, "unreferenced: no input object, the host image or a hot symbol uses it")
        elif name in hot:
            plan["conflict"][name] = (copies, "hot symbol (hot symbols are never weakened or localized)")
        else:
            bodies = [symbol_body(extract_member(c[0], c[1], work), name) for c in copies]
            if any(b is None for b in bodies):
                plan["conflict"][name] = (copies, "referenced, and not code that can be compared byte for byte")
            elif any(b != bodies[0] for b in bodies[1:]):
                plan["conflict"][name] = (copies, "referenced, and the copies differ: " + ", ".join(
                    f"{c[2]} {len(b[0]) * 4} bytes sha {body_digest(b)}" for c, b in zip(copies, bodies)))
            else:
                b = bodies[0]
                plan["weak"][name] = (copies, f"identical: {len(b[0])} instructions ({len(b[0]) * 4} bytes), "
                                              f"{len(b[1])} relocation(s), sha256 {body_digest(b)} in all {len(copies)} copies")
    return plan


def apply_duplicate_plan(plan, work):
    """Rewrites the archives that hold a copy (into the work directory; the payloads and the merge cache stay as they
    are). Returns {original input: rewritten input}."""
    edits = {}  # input -> member -> [("local"|"weak", name)]
    for name, (copies, _) in plan["local"].items():
        for inp, member, _lib, _cache in copies:
            edits.setdefault(inp, {}).setdefault(member, []).append(("local", name))
    for name, (copies, _) in plan["weak"].items():
        for inp, member, _lib, _cache in copies[1:]:
            edits.setdefault(inp, {}).setdefault(member, []).append(("weak", name))
    replaced = {}
    for inp, members in edits.items():
        tag = hashlib.sha256(inp.encode()).hexdigest()[:16]
        if inp.endswith(".a"):
            d = os.path.join(work, "dups", tag)
            os.makedirs(d)
            order = subprocess.check_output(["ar", "t", inp], text=True, errors="replace").splitlines()
            subprocess.run(["ar", "x", inp], cwd=d, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            d = os.path.join(work, "dups", tag)
            os.makedirs(d)
            shutil.copyfile(inp, os.path.join(d, os.path.basename(inp)))
            order = [os.path.basename(inp)]
        for member, ops in members.items():
            obj = os.path.join(d, member or os.path.basename(inp))
            local = [n for kind, n in ops if kind == "local"]
            if local:
                cmd = [LLVM_OBJCOPY] + [f"--localize-symbol={n}" for n in local] + [obj]
                r = subprocess.run(cmd, capture_output=True, text=True)
                if r.returncode != 0:
                    raise SystemExit(f"merge-payloads: llvm-objcopy --localize-symbol failed on {obj}: {r.stderr.strip()}")
            for kind, n in ops:
                if kind == "weak" and weaken_macho_symbol(obj, n) != 1:
                    raise SystemExit(f"merge-payloads: could not make {n} weak in {obj}")
        if inp.endswith(".a"):
            out = os.path.join(work, "dups", tag + "-" + os.path.basename(inp))
            members_in_order = [m for m in order if not m.startswith("__.SYMDEF")]
            subprocess.run(["ar", "rcs", out] + members_in_order, cwd=d, check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            out = os.path.join(d, os.path.basename(inp))
        replaced[inp] = out
    return replaced


def report_duplicates(plan, where):
    """Prints the tier counts and every symbol with its copies and the reason; returns True when linking may go on."""
    n = {k: len(v) for k, v in plan.items()}
    print(f"duplicate definitions across libraries ({where}): tier 1 local={n['local']} tier 2 weak={n['weak']} "
          f"tier 3 conflict={n['conflict']}; same library={n['same_library']}")
    for tier, label in (("local", "tier 1 local"), ("weak", "tier 2 weak"), ("conflict", "tier 3 conflict")):
        for name, (copies, why) in plan[tier].items():
            print(f"  {label}: {name}")
            print(f"    copies: " + "; ".join(f"{lib} ({cache})" for _i, _m, lib, cache in copies))
            print(f"    basis: {why}")
    for name, (copies, why) in plan["conflict"].items():
        print(f"error: {name} is defined by several libraries with different code and is used: "
              + "; ".join(f"{lib} (cache {cache})" for _i, _m, lib, cache in copies)
              + ". This is a semantic conflict that needs a human decision: hot reload links per file and will not pick "
                "one copy by link order the way a whole-program link silently would.")
    for name, (copies, _) in plan["same_library"].items():
        print(f"error: {name} is defined by two files of one library: "
              + "; ".join(f"{lib} (cache {cache})" for _i, _m, lib, cache in copies)
              + ". These are distinct declarations that only share a name (for example lambdas named after a function "
                "that two files of one package both have); the toolchain must name them per file (Issue #91).")
    return not plan["conflict"] and not plan["same_library"]


def collect_copies(inputs, tables, src_of, extra_refs):
    """({name: copies} for the names more than one input defines strongly, names some input or [extra_refs] uses)."""
    copies_by_name, refs = {}, set(extra_refs)
    for inp in inputs:
        t = tables.get(inp)
        if not t:
            continue
        lib, cache = library_of(src_of.get(inp, inp))
        for name, member in t["def"].items():
            copies_by_name.setdefault(name, []).append((inp, member, lib, cache))
        refs.update(t["undef"])
    return {k: v for k, v in copies_by_name.items() if len(v) > 1}, refs


def resolve_duplicates(inputs, tables, src_of, extra_refs, hot, work, where):
    """Plans, reports and applies the tiers for one set of inputs; returns (replacements, plan) or exits on a conflict."""
    copies_by_name, refs = collect_copies(inputs, tables, src_of, extra_refs)
    plan = plan_duplicates(copies_by_name, refs, hot, work)
    if not report_duplicates(plan, where):
        raise SystemExit(1)
    return apply_duplicate_plan(plan, work), plan


def patch_dups(record_path, hot_path, labelled):
    """--patch-dups: the patch path's counterpart of the packaging rule (Issue #91). [labelled]: (module, object) pairs.

    1. A patch object that uses a name the packaged app keeps only as local copies (tier 1) cannot be linked the way the
       whole-program link would: print `WARM duplicate-declaration ...` and exit 3 (the plugin restarts warm).
    2. A patch object that defines such a name (a downstream module's file was rebuilt) gets a local copy too, so the
       name stays unlinked, as in the packaged app.
    3. Names that objects of two or more modules of this patch define: the same three tiers as for packaging.
    Objects are rewritten in place (they are the patch's own extracted copies)."""
    record = {}
    if os.path.isfile(record_path):
        record = json.load(open(record_path))
    local_names = set(record.get("local", []))
    hot = set(l.strip() for l in open(hot_path)) if os.path.isfile(hot_path) else set()
    tables = {obj: nm_table(obj) for _m, obj in labelled}
    used = []
    for module, obj in labelled:
        for name in tables[obj]["undef"]:
            if name in local_names:
                used.append((name, module))
    if used:
        for name, module in used:
            print(f"WARM duplicate-declaration {name}: the patch of {module} uses it, but the packaged app keeps only "
                  "local copies of it (several libraries define it differently and nothing used it at packaging); "
                  "a warm restart links it again")
        return 3
    localize = {}
    for module, obj in labelled:
        for name in tables[obj]["def"]:
            if name in local_names:
                localize.setdefault(obj, []).append(name)
    for obj, names in localize.items():
        r = subprocess.run([LLVM_OBJCOPY] + [f"--localize-symbol={n}" for n in names] + [obj], capture_output=True, text=True)
        if r.returncode != 0:
            print(f"error: llvm-objcopy --localize-symbol failed on {obj}: {r.stderr.strip()}")
            return 1
        tables[obj] = nm_table(obj)
    print(f"patch: {sum(len(v) for v in localize.values())} definition(s) of names the packaged app keeps local, made local")
    copies_by_name, refs = {}, set()
    for module, obj in labelled:
        for name, member in tables[obj]["def"].items():
            copies_by_name.setdefault(name, []).append((obj, member, module, os.path.basename(obj)))
        refs.update(tables[obj]["undef"])
    copies_by_name = {k: v for k, v in copies_by_name.items() if len(v) > 1}
    work = tempfile.mkdtemp(prefix="kaldo-patch-dups-")
    try:
        plan = plan_duplicates(copies_by_name, refs, hot, work)
        if not report_duplicates(plan, "patch"):
            return 1
        for name, (copies, _) in plan["local"].items():
            for obj, _m, _l, _c in copies:
                subprocess.run([LLVM_OBJCOPY, f"--localize-symbol={name}", obj], check=True)
        for name, (copies, _) in plan["weak"].items():
            for obj, _m, _l, _c in copies[1:]:
                if weaken_macho_symbol(obj, name) != 1:
                    print(f"error: could not make {name} weak in {obj}")
                    return 1
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return 0


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


def members_objc_classes(paths):
    """{path: defined _OBJC_CLASS_$_ symbols} for several objects from batched `nm -g` (one call per batch).

    ObjC class symbols are external, so `nm -g` sees the same defined classes as `nm -m` per file did."""
    result = {p: set() for p in paths}
    for p, lines in nm_global_lines(paths).items():
        for line in lines:
            parts = line.split()
            if len(parts) >= 2 and parts[-1].startswith("_OBJC_CLASS_$_") and parts[-2] not in ("U", "w"):
                result[p].add(parts[-1])
    return result




def objc_member_is_host_backed(member_path, host):
    classes = member_objc_classes(member_path)
    return bool(classes) and classes <= host


NM_BATCH = 400


def nm_global_lines(paths):
    """`nm -g` output lines per file, one nm process per batch of files.

    Same output as one `nm -g <file>` per file (that cost ~10 ms per object, so
    ~20 s for the ~3000 cache members of a Compose app). nm runs in each file's
    directory with the bare name, so a "<name>:" line is exactly a file header.
    A file nm cannot read gets no lines, as before.
    """
    by_dir = {}
    for p in paths:
        by_dir.setdefault(os.path.dirname(p), []).append(os.path.basename(p))
    result = {}
    for d, names in by_dir.items():
        for i in range(0, len(names), NM_BATCH):
            batch = names[i:i + NM_BATCH]
            out = subprocess.run(["nm", "-g"] + batch, cwd=d or None,
                                 capture_output=True, text=True,
                                 errors="replace").stdout
            want = set(batch)
            cur = batch[0] if len(batch) == 1 else None
            for line in out.splitlines():
                if len(batch) > 1 and line.endswith(":") and line[:-1] in want:
                    cur = line[:-1]
                    continue
                if cur is not None:
                    result.setdefault(os.path.join(d, cur), []).append(line)
    for lines in result.values():  # the blank line nm prints before the next header
        while lines and lines[-1] == "":
            lines.pop()
    return result


def collect_hot_symbols(members):
    """Record the Kotlin symbols of the user module (the object that defines `#main`).

    Only these need redirectable stubs: a reload replaces just the user module.
    Externalizing every Compose symbol (100k+) bloats ORC's dependence graph and
    occasionally crashes simplifyDepGroups.
    """
    names = set()
    hit = False
    paths = [m if os.path.isabs(m) else os.path.join(os.path.dirname(m), m) for m in members]
    lines = nm_global_lines([p for p in paths if os.path.isfile(p)])
    for p in paths:
        if p not in lines:
            continue
        syms = [line.split()[-1] for line in lines[p] if len(line.split()) >= 2]
        is_user_module = any(s.startswith("_kfun:#main") or s.startswith("_kfun:main") for s in syms)
        if not is_user_module:
            for pkg in HOT_PACKAGES:
                if any(s.startswith("_kfun:" + pkg + ".") or s.startswith("_kfun:" + pkg + "#") for s in syms):
                    is_user_module = True
                    break
        if is_user_module:
            hit = True
            names.update(s for s in syms if s.startswith("_kfun:"))
    return sorted(names) if hit else []


def write_hot_symbols(names, src):
    if not names:
        return
    with open(HOT_SYMBOLS_OUT, "a") as f:
        for n in names:
            f.write(n + "\n")
    print(f"hot symbols: {len(names)} (from {os.path.basename(src)})")


def dedup_archive(src, dst):
    """Returns (hot symbol names, whether the host image was consulted)."""
    global skipped_objc
    host_used = False
    tmp = tempfile.mkdtemp(prefix="dedup-")
    try:
        subprocess.run(["ar", "x", src], cwd=tmp, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        members = sorted(m for m in os.listdir(tmp) if not m.startswith("__.SYMDEF"))
        if os.environ.get("KALDO_GUEST_KEEP_OBJC") != "1":
            # ObjC classes of every candidate member from one nm call (#32: one call per member cost ~0.4 s).
            objc_classes = members_objc_classes(
                [os.path.join(tmp, m) for m in members if m.startswith(SKIP_OBJC_PREFIX) and m.endswith(".o")])
            kept = []
            for m in members:
                p = os.path.join(tmp, m)
                if m.startswith(SKIP_OBJC_PREFIX) and m.endswith(".o"):
                    host_used = True
                if (m.startswith(SKIP_OBJC_PREFIX) and m.endswith(".o")
                        and objc_classes.get(p)
                        and objc_classes[p] <= host_symbol_table(HOST_KEXE)):
                    skipped_objc += 1
                    print(f"guest: dropping ObjC member {m} (host copy is used)")
                elif (m.endswith(".o") and os.environ.get("KALDO_GUEST_DROP_CXX") == "1"
                        and member_is_cxx_only(p)):
                    skipped_objc += 1
                    print(f"guest: dropping C++ member {m} (host copy is used)")
                else:
                    kept.append(m)
            members = kept
        hot = collect_hot_symbols([os.path.join(tmp, m) for m in members])
        subprocess.run(["ar", "rcs", dst] + members, cwd=tmp, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    finally:
        shutil.rmtree(tmp)
    return hot, host_used


# Per-archive result cache (KALDO_MERGE_CACHE_DIR, off when unset). Library
# cache archives are identical from one package to the next; their processed
# form (ObjC/C++ members the host provides removed) and hot symbols are kept under
# sha256(this script, flags, archive bytes). Archives whose processing looked at
# the host image (ObjC members) are never cached. After a successful merge,
# entries this merge did not use are removed, so the cache holds one app's set.
MERGE_CACHE = os.environ.get("KALDO_MERGE_CACHE_DIR", "")
CACHE_ENTRY = re.compile(r"^[0-9a-f]{64}\.(a|json|o|syms\.json)$")


def cache_key(data):
    h = hashlib.sha256()
    h.update(open(os.path.abspath(__file__), "rb").read())
    h.update(repr((os.environ.get("KALDO_GUEST_KEEP_OBJC"),
                   os.environ.get("KALDO_GUEST_DROP_CXX"), HOT_PACKAGES)).encode())
    h.update(data)
    return h.hexdigest()


def cache_get(key):
    a = os.path.join(MERGE_CACHE, key + ".a")
    meta = os.path.join(MERGE_CACHE, key + ".json")
    if not (os.path.isfile(a) and os.path.isfile(meta)):
        return None
    try:
        return a, json.load(open(meta))
    except (OSError, ValueError):
        return None


def cache_put(key, archive, meta):
    """Moves the processed archive into the cache; returns its new path."""
    os.makedirs(MERGE_CACHE, exist_ok=True)
    dst = os.path.join(MERGE_CACHE, key + ".a")
    tmp = f"{dst}.{os.getpid()}.tmp"
    try:
        os.replace(archive, tmp)
    except OSError:  # another volume
        shutil.copyfile(archive, tmp)
    os.replace(tmp, dst)
    mtmp = os.path.join(MERGE_CACHE, f"{key}.json.{os.getpid()}.tmp")
    with open(mtmp, "w") as f:
        json.dump(meta, f)
    os.replace(mtmp, os.path.join(MERGE_CACHE, key + ".json"))
    return dst


def cache_prune(used):
    if not MERGE_CACHE or not os.path.isdir(MERGE_CACHE):
        return
    for f in os.listdir(MERGE_CACHE):
        if (CACHE_ENTRY.match(f) or f.endswith(".tmp")) and f.split(".")[0] not in used:
            os.remove(os.path.join(MERGE_CACHE, f))


def link_and_fix(inputs, out):
    """ld -r the inputs into one object, then the fix-ups JITLink needs (see below)."""
    ld = subprocess.check_output(["xcrun", "--sdk", "iphonesimulator", "-f", "ld"]).decode().strip()
    sdk = subprocess.check_output(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-version"]).decode().strip()
    cmd = [ld, "-r", "-arch", "arm64",
           "-platform_version", "ios-simulator", "15.0", sdk,
           "-o", out, "-multiply_defined", "suppress"]
    for p in inputs:
        cmd += ["-force_load", p]
    print("ld -r ...")
    run(cmd)
    # The compiler-rt object's symbols are private-external; globalize the
    # availability helpers so the JIT can resolve them by name.
    # ld -r demotes the ObjC export adapter tables (N_PEXT in the payloads) to
    # local symbols, so the JIT cannot resolve them by name and Kaldo reads the
    # zero-filled weak fallback instead. Globalize them back to private-external.
    globalize = []
    for sym in ("___isPlatformVersionAtLeast", "___isOSVersionAtLeast",
                "_Kotlin_ObjCInterop_uniquePrefix",
                "_Kotlin_ObjCExport_sortedClassAdapters",
                "_Kotlin_ObjCExport_sortedClassAdaptersNum",
                "_Kotlin_ObjCExport_sortedProtocolAdapters",
                "_Kotlin_ObjCExport_sortedProtocolAdaptersNum",
                "_Kotlin_ObjCExport_sortedCategoryAdapters",
                "_Kotlin_ObjCExport_sortedCategoryAdaptersNum"):
        globalize += ["--globalize-symbol", sym]
    # One pass over the ~130 MB object instead of one per symbol.
    subprocess.run([LLVM_OBJCOPY] + globalize + [out],
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


def main():
    if len(sys.argv) >= 3 and sys.argv[1] == "--rename-compact-unwind":
        # merge-payloads.py --rename-compact-unwind <object>...: the patch path (hotreload.sh reload, the plugin's
        # mergePatch, the device build-ios-patch.sh) calls this on the object it sends.
        for path in sys.argv[2:]:
            if not rename_compact_unwind(path):
                print(f"compact unwind: nothing to rename in {path}")
        return 0
    if len(sys.argv) >= 5 and sys.argv[1] == "--patch-dups":
        labelled = []
        for arg in sys.argv[4:]:
            module, sep, obj = arg.partition("=")
            if not sep:
                print(f"--patch-dups: expected <module>=<object>, got {arg}")
                return 2
            labelled.append((module, obj))
        return patch_dups(sys.argv[2], sys.argv[3], labelled)
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
    payloads = []
    sources = []  # original payload paths (payload-list mode only)
    keys = {}
    libs_out = os.path.splitext(out)[0] + ".libs.o"
    if os.path.lexists(libs_out):
        os.remove(libs_out)
    if PAYLOAD_LIST:
        # The compiler wrote the payload paths (-Xbinary=splitPayloadList=...) instead of embedding the payloads:
        # read them from disk in the same order (#32). Each archive is copied, like the embedded ones were.
        paths = [l.strip() for l in open(PAYLOAD_LIST) if l.strip()]
        for src in paths:
            # Used in place (read only; every rewrite below goes to a copy in the work directory).
            sources.append(src)
            payloads.append(src)
            if MERGE_CACHE and src.endswith(".a"):
                with open(src, "rb") as f:
                    keys[src] = cache_key(f.read())
        print(f"payload list: {PAYLOAD_LIST}")
    else:
        raw = os.path.join(work, "boot.bin")
        dump_section(kexe, raw)
        blob = open(raw, "rb").read()
        entries, base = parse_manifest(blob)
        for i, (kind, o, s) in enumerate(entries):
            data = blob[base + o:base + o + s]
            ext = "o" if kind == 0 else "a"
            p = os.path.join(work, f"p{i:02d}.{ext}")
            open(p, "wb").write(data)
            payloads.append(p)
            if MERGE_CACHE and ext == "a":
                keys[p] = cache_key(data)
    print(f"payloads: {len(payloads)}, total {sum(os.path.getsize(p) for p in payloads)} bytes")

    inputs = []
    lib_inputs = set()   # inputs that belong to the cached library object (#32 step 2)
    lib_keys = []        # their cache keys, in order
    split_ok = bool(LIB_PAYLOAD_DIRS and PAYLOAD_LIST and MERGE_CACHE)
    def is_lib(p):
        if not split_ok:
            return False
        src = sources[payloads.index(p)]
        return any(src.startswith(d) for d in LIB_PAYLOAD_DIRS)
    # compiler-rt availability check object (real OS version semantics, not a stub).
    shim = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "compiler-rt-os_version_check.o")
    if os.path.exists(shim):
        inputs.append(shim)
        lib_inputs.add(shim)
        print(f"shim: {shim}")
    used, reused = set(), 0
    src_of, key_of = {}, {}  # input -> payload it came from / merge-cache key (Issue #91)
    for p in payloads:
        if p.endswith(".a"):
            key = keys.get(p)
            hit = cache_get(key) if key else None
            if hit:
                cached, meta = hit
                write_hot_symbols(meta["hot"], p)
                inputs.append(cached)
                src_of[cached] = sources[payloads.index(p)] if sources else p
                key_of[cached] = key
                used.add(key)
                reused += 1
                if is_lib(p):
                    lib_inputs.add(cached)
                    lib_keys.append(key)
                continue
            dedup = os.path.join(work, f"d{payloads.index(p):02d}-" + os.path.basename(p))
            hot, host_used = dedup_archive(p, dedup)
            write_hot_symbols(hot, p)
            if key and not host_used:
                dedup = cache_put(key, dedup, {"hot": hot})
                used.add(key)
                key_of[dedup] = key
                if is_lib(p):
                    lib_inputs.add(dedup)
                    lib_keys.append(key)
            # A library archive that cannot be cached (processing looked at the host image, e.g. ui-uikit with its
            # CMP* ObjC members) is reprocessed every time anyway; it goes into the user object.
            inputs.append(dedup)
            src_of[dedup] = sources[payloads.index(p)] if sources else p
        else:
            inputs.append(p)
            src_of[p] = p
    if MERGE_CACHE:
        print(f"merge cache: {reused}/{len(keys)} archives reused ({MERGE_CACHE})")

    # Issue #91: names that the caches of several libraries define, over every input (library object and user object).
    tables = {inp: cached_nm_table(inp, key_of.get(inp)) for inp in inputs}
    host_refs = set(subprocess.run(["nm", "-u", "-j", kexe], capture_output=True, text=True).stdout.split())
    hot_names = set(l.strip() for l in open(HOT_SYMBOLS_OUT) if l.strip())
    replaced, plan = resolve_duplicates(inputs, tables, src_of, host_refs, hot_names, work, "packaging")
    lib_plan = sorted(n for tier in ("local", "weak") for n, (copies, _) in plan[tier].items()
                      if any(c[0] in lib_inputs for c in copies))
    inputs = [replaced.get(p, p) for p in inputs]
    lib_inputs = {replaced.get(p, p) for p in lib_inputs}
    with open(os.path.splitext(out)[0] + DUPS_RECORD_SUFFIX, "w") as f:
        json.dump({"local": sorted(plan["local"]), "weak": sorted(plan["weak"])}, f, indent=1)

    if split_ok and lib_keys:
        h = hashlib.sha256()
        h.update(open(os.path.abspath(__file__), "rb").read())
        h.update(repr((os.environ.get("KALDO_KEEP_COMPACT_UNWIND"), "libs")).encode())
        h.update("".join(lib_keys).encode())
        h.update(repr(lib_plan).encode())  # the tiers applied to library archives depend on the user objects' uses
        lkey = h.hexdigest()
        cached_libs = os.path.join(MERGE_CACHE, lkey + ".o")
        used.add(lkey)
        if os.path.isfile(cached_libs):
            print(f"library object: reused {cached_libs}")
        else:
            tmp = f"{cached_libs}.{os.getpid()}.tmp"
            link_and_fix([p for p in inputs if p in lib_inputs], tmp)
            os.replace(tmp, cached_libs)
            print(f"library object: built {cached_libs} ({os.path.getsize(cached_libs)} bytes)")
        try:
            os.link(cached_libs, libs_out)
        except OSError:
            shutil.copyfile(cached_libs, libs_out)
        link_and_fix([p for p in inputs if p not in lib_inputs], out)
        print(f"library payload: {libs_out}")
    else:
        link_and_fix(inputs, out)
    print(f"merged: {out} ({os.path.getsize(out)} bytes)")
    cache_prune(used)
    return 0


if __name__ == "__main__":
    sys.exit(main())
