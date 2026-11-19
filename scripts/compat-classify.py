#!/usr/bin/env python3
"""Compatibility classifier for hot reload (interface v8).

Commands:

  compat-classify.py snapshot --klib <path> --out <json> [--klib-tool <klib>]
                              [--daemon <sock>] [--cache-dir <dir>]
  compat-classify.py classify --old <json> --new <json> [--json]
  compat-classify.py baseline --baseline-dir <dir> --module <name>=<klib> ...
                              [--klib-tool <klib>] [--daemon <sock>] [--cache-dir <dir>]
  compat-classify.py check    --baseline-dir <dir> --module <name>=<klib|-> ...
                              [--klib-tool <klib>] [--daemon <sock>] [--cache-dir <dir>]
                              [--prior-ms <name>=<ms>]

`baseline` writes <dir>/<name>.json for every module. `check` snapshots every
module, writes <dir>/<name>.new.json and classifies it against <dir>/<name>.json
in one process (v8; `snapshot` + `classify` per module still work). A module
given as `<name>=-` is unchanged: its baseline is reused. A klib whose sha256
equals the one recorded in its baseline is unchanged too and is not dumped.
`--daemon` runs the klib dumps inside the hot-reload compile daemon (`@klib`
request, interface v8) and falls back to the `klib` tool when it is not
reachable. `--cache-dir` keeps snapshots keyed by this script's hash and the
klib's sha256. `check` prints one diagnostic timing line (not a contract):
  compat: timing ms=.. ui_snapshot=.. app_snapshot=.. classify=.. <name>=<same|hit|dump> ..

`snapshot` runs the Kotlin/Native `klib` tool (`dump-metadata`, `dump-ir`) and
stores a normalized JSON description of the module: declarations, class field
layout, and the bodies of inline/suspend functions.

`classify` compares two snapshots and decides whether the change can be hot
replaced or needs a warm restart. Exit codes:

  0  hot replaceable
  3  needs warm restart (reasons printed, one per line)
  2  usage or tool error

Warm reasons (TASK.md Phase 2):
  field-layout         class field added/removed/type/order changed, or a
                       constructor signature changed
  signature            function signature changed, method added to a class,
                       or supertypes changed
  inline               body of an inline function changed
  suspend              body of a suspend function changed
  top-level-property   top-level property added/removed/type/var-val changed
  class-removed        a class disappeared (old instances may still be alive)

Allowed hot changes (must not be flagged): function bodies of ordinary
functions, added/removed top-level functions (composables), private method
changes, added classes.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile

SNAPSHOT_KIND = "cmp-hot-reload-compat"
SNAPSHOT_VERSION = 1

MODIFIERS = {
    "public", "private", "internal", "protected", "final", "open", "abstract",
    "sealed", "enum", "annotation", "data", "inner", "value", "fun",
    "companion", "external", "expect", "actual", "inline", "tailrec",
    "operator", "infix", "const", "lateinit", "suspend", "override",
}

CLASS_RE = re.compile(
    r"^(?P<mods>(?:(?:" + "|".join(sorted(MODIFIERS, key=len, reverse=True)) +
    r")\s+)*)(?P<kind>class|interface|object)\s+(?P<name>[^\s:{]+)"
    r"(?:\s*:\s*(?P<supers>.+?))?\s*\{$")
FUN_RE = re.compile(
    r"^(?P<mods>(?:(?:" + "|".join(sorted(MODIFIERS, key=len, reverse=True)) +
    r")\s+)*)fun\s+(?P<name>[^(]+)\((?P<params>.*)\)"
    r"(?:\s*:\s*(?P<ret>.+?))?$")
CTOR_RE = re.compile(
    r"^(?P<mods>(?:(?:" + "|".join(sorted(MODIFIERS, key=len, reverse=True)) +
    r")\s+)*)constructor\((?P<params>.*)\)$")
PROP_RE = re.compile(
    r"^(?P<mods>(?:(?:" + "|".join(sorted(MODIFIERS, key=len, reverse=True)) +
    r")\s+)*)(?P<kind>val|var)\s+(?P<name>[^\s:]+)\s*:\s*(?P<type>.+?)$")


def warn(msg):
    print(msg, file=sys.stderr)


def klib_command(tool, command, path):
    r = subprocess.run([tool, command, path], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"klib {command} failed ({r.returncode}): "
                           f"{(r.stderr or r.stdout).strip()[:400]}")
    return r.stdout


_daemon_failed = set()


def daemon_klib_command(sock, command, path):
    """Run `klib <command> <path>` inside the compile daemon. None if unusable."""
    import socket
    if not sock or sock in _daemon_failed or not os.path.exists(sock):
        return None
    fd, out = tempfile.mkstemp(prefix="compat-dump-", suffix=".txt")
    os.close(fd)
    try:
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            s.connect(sock)
            s.sendall(("\n".join(["@klib", out, command, os.path.abspath(path)]) + "\n\n").encode())
            buf = b""
            while not buf.endswith(b"\n"):
                chunk = s.recv(4096)
                if not chunk:
                    break
                buf += chunk
        finally:
            s.close()
        line = buf.decode(errors="replace").strip()
        if not line.startswith("OK"):
            warn(f"compat: daemon klib {command} failed ({line or 'no answer'}); using the klib tool")
            _daemon_failed.add(sock)
            return None
        with open(out, encoding="utf-8") as f:
            return f.read()
    except OSError as e:
        warn(f"compat: klib daemon not reachable ({e}); using the klib tool")
        _daemon_failed.add(sock)
        return None
    finally:
        try:
            os.unlink(out)
        except OSError:
            pass


def dump(tool, sock, command, path):
    text = daemon_klib_command(sock, command, path)
    if text is not None:
        return text
    if not tool or not os.path.isfile(tool):
        raise RuntimeError("no klib tool (use --klib-tool or KOTLIN_DIST/KLIB_TOOL)")
    return klib_command(tool, command, path)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def script_hash():
    return sha256_file(os.path.abspath(__file__))[:16]


def mods_of(text):
    return set(text.split())


def split_type(type_text):
    """Drop an initializer comment from a metadata property type."""
    type_text = re.sub(r"\s*/\*\s*=.*?\*/\s*$", "", type_text)
    return type_text.strip()


def parse_metadata(text):
    classes = {}
    top_props = {}
    top_funs = {}
    top_other = []
    package = ""
    # Stack entries: (indent, fq_name, container dict)
    stack = []
    for raw in text.splitlines():
        if not raw.strip():
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        line = raw.strip()
        if line.startswith("//"):
            m = re.match(r"//\s*package name:\s*(.*)$", line)
            if m:
                package = "" if m.group(1) == "<root>" else m.group(1)
            continue
        if line in ("library {", "library fragment {") or line == "package {":
            continue
        if line.startswith("@"):
            # Annotation lines (Compose adds several synthetic ones) carry no
            # layout information; adding an annotated declaration is allowed.
            continue
        if line == "}":
            while stack and stack[-1][0] >= indent:
                stack.pop()
            continue
        while stack and stack[-1][0] >= indent:
            stack.pop()
        in_class = bool(stack)
        container = stack[-1][2] if in_class else None

        m = CLASS_RE.match(line)
        if m:
            name = m.group("name")
            info = {
                "kind": m.group("kind"),
                "visibility": visibility_of(m.group("mods")),
                "modality": modality_of(m.group("mods")),
                "superTypes": [s.strip() for s in (m.group("supers") or "").split(",") if s.strip()],
                "constructors": [],
                "properties": [],
                "functions": [],
                "other": [],
            }
            if in_class:
                container.setdefault("nested", {})[name] = info
            else:
                classes[name] = info
            stack.append((indent, name, info))
            continue

        m = CTOR_RE.match(line)
        if m and in_class:
            container["constructors"].append({
                "visibility": visibility_of(m.group("mods")),
                "params": m.group("params").strip(),
            })
            continue

        m = FUN_RE.match(line)
        if m:
            entry = {
                "name": m.group("name").strip(),
                "params": m.group("params").strip(),
                "return": (m.group("ret") or "kotlin/Unit").strip(),
                "visibility": visibility_of(m.group("mods")),
                "suspend": "suspend" in mods_of(m.group("mods")),
                "inline": "inline" in mods_of(m.group("mods")),
            }
            if in_class:
                container["functions"].append(entry)
            else:
                top_funs[entry["name"]] = entry
            continue

        m = PROP_RE.match(line)
        if m:
            entry = {
                "name": m.group("name"),
                "type": split_type(m.group("type")),
                "mutable": m.group("kind") == "var",
                "visibility": visibility_of(m.group("mods")),
            }
            if in_class:
                container["properties"].append(entry)
            else:
                top_props[entry["name"]] = entry
            continue

        # Accessor lines ("public final get"/"set") belong to the property
        # above them; they add no information the property diff does not have.
        if re.match(r"^(?:\w+\s+)*(?:get|set)$", line):
            continue
        if in_class:
            container["other"].append(normalize_line(line))
        else:
            top_other.append(normalize_line(line))
    return classes, top_props, top_funs, top_other


def visibility_of(mods_text):
    mods = mods_of(mods_text)
    for v in ("public", "internal", "protected", "private"):
        if v in mods:
            return v
    return "public"


def modality_of(mods_text):
    mods = mods_of(mods_text)
    for v in ("final", "open", "abstract", "sealed", "enum"):
        if v in mods:
            return v
    return "final"


def normalize_line(line):
    line = re.sub(r"file(Name)?:[^\s]+", "file:<path>", line)
    line = re.sub(r"local\((\d+)\)", r"local(\1)", line)
    return re.sub(r"\s+", " ", line).strip()


IR_FUN_RE = re.compile(r"^(\s*)FUN\b.*?signature:(\S+)(.*)$")


def parse_ir(text):
    """Extract inline/suspend function bodies, keyed by IR signature."""
    lines = text.splitlines()
    bodies = {}
    i = 0
    while i < len(lines):
        m = IR_FUN_RE.match(lines[i])
        if not m:
            i += 1
            continue
        indent, signature, rest = len(m.group(1)), m.group(2), m.group(3)
        flags = re.search(r"\[([^\]]*)\]", rest)
        kinds = []
        if flags:
            words = {w.strip() for w in flags.group(1).split(",")}
            if "inline" in words:
                kinds.append("inline")
            if "suspend" in words:
                kinds.append("suspend")
        block = [lines[i]]
        j = i + 1
        while j < len(lines):
            nxt = lines[j]
            if nxt.strip():
                nindent = len(nxt) - len(nxt.lstrip(" "))
                if nindent <= indent:
                    break
            block.append(nxt)
            j += 1
        if kinds:
            body = "\n".join(normalize_line(x) for x in block)
            bodies[signature] = {
                "kind": "+".join(kinds),
                "hash": hashlib.sha256(body.encode()).hexdigest(),
            }
        i = j
    return bodies


def snapshot(klib, tool, sock=None, klib_sha=None):
    meta = dump(tool, sock, "dump-metadata", klib)
    ir = dump(tool, sock, "dump-ir", klib)
    classes, top_props, top_funs, top_other = parse_metadata(meta)
    bodies = parse_ir(ir)
    module = os.path.basename(klib)
    if module.endswith(".klib"):
        module = module[:-5]
    return {
        "kind": SNAPSHOT_KIND,
        "version": SNAPSHOT_VERSION,
        "module": module,
        "klib": os.path.basename(klib),
        "classes": classes,
        "topProperties": top_props,
        "topFunctions": top_funs,
        "topOther": sorted(top_other),
        "bodies": bodies,
        "klibSha256": klib_sha or sha256_file(klib),
    }


def snapshot_cached(klib, tool, sock, cache_dir):
    """Returns (snapshot, mode) with mode 'hit' or 'dump'."""
    sha = sha256_file(klib)
    key = None
    if cache_dir:
        os.makedirs(cache_dir, exist_ok=True)
        key = os.path.join(cache_dir, f"{script_hash()}-{sha}.json")
        if os.path.exists(key):
            with open(key) as f:
                data = json.load(f)
            data["module"] = os.path.basename(klib)[:-5] if klib.endswith(".klib") else os.path.basename(klib)
            data["klib"] = os.path.basename(klib)
            os.utime(key)
            return data, "hit"
    data = snapshot(klib, tool, sock, sha)
    if key:
        write_json(key, data)
        prune_cache(cache_dir)
    return data, "dump"


def prune_cache(cache_dir, keep=64):
    files = [os.path.join(cache_dir, f) for f in os.listdir(cache_dir) if f.endswith(".json")]
    files.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    for old in files[keep:]:
        try:
            os.unlink(old)
        except OSError:
            pass


def write_json(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(data, f, indent=1, sort_keys=True)
        f.write("\n")
    os.replace(tmp, path)


def load(path):
    with open(path) as f:
        data = json.load(f)
    if data.get("kind") != SNAPSHOT_KIND:
        raise SystemExit(f"not a compatibility snapshot: {path}")
    return data


def reasons_for(old, new):
    reasons = []

    def add(category, symbol, detail):
        reasons.append({"category": category, "symbol": symbol, "detail": detail})

    old_classes = old["classes"]
    new_classes = new["classes"]
    for fq in sorted(set(old_classes) - set(new_classes)):
        add("class-removed", fq, "class removed")

    for fq in sorted(set(old_classes) & set(new_classes)):
        a, b = old_classes[fq], new_classes[fq]
        if a["superTypes"] != b["superTypes"]:
            add("signature", fq, f"supertypes changed: {a['superTypes']} -> {b['superTypes']}")
        if a["constructors"] != b["constructors"]:
            add("field-layout", fq, "constructor signature changed")
        if a["properties"] != b["properties"]:
            add("field-layout", fq, field_diff(a["properties"], b["properties"]))
        old_fns = {(f["name"], f["params"]): f for f in a["functions"]}
        new_fns = {(f["name"], f["params"]): f for f in b["functions"]}
        for key in sorted(set(old_fns) - set(new_fns)):
            f = old_fns[key]
            if f["visibility"] == "private":
                continue
            cat = "inline" if f["inline"] else ("suspend" if f["suspend"] else "signature")
            add(cat, f"{fq}.{key[0]}", "method removed")
        for key in sorted(set(new_fns) - set(old_fns)):
            f = new_fns[key]
            if f["visibility"] == "private":
                continue
            cat = "inline" if f["inline"] else ("suspend" if f["suspend"] else "signature")
            add(cat, f"{fq}.{key[0]}", "method added to existing class")
        for key in sorted(set(old_fns) & set(new_fns)):
            a_fn, b_fn = old_fns[key], new_fns[key]
            if a_fn["visibility"] == "private" and b_fn["visibility"] == "private":
                continue
            if a_fn["return"] != b_fn["return"]:
                cat = "inline" if (a_fn["inline"] or b_fn["inline"]) else (
                    "suspend" if (a_fn["suspend"] or b_fn["suspend"]) else "signature")
                add(cat, f"{fq}.{key[0]}", f"return type {a_fn['return']} -> {b_fn['return']}")
        if a.get("other", []) != b.get("other", []):
            add("class-shape", fq, "unclassified class member changed")

    old_props, new_props = old["topProperties"], new["topProperties"]
    for name in sorted(set(old_props) | set(new_props)):
        if name not in old_props:
            add("top-level-property", name, "top-level property added")
        elif name not in new_props:
            add("top-level-property", name, "top-level property removed")
        elif old_props[name] != new_props[name]:
            add("top-level-property", name,
                f"type/mutability changed: {old_props[name]} -> {new_props[name]}")

    old_funs, new_funs = old["topFunctions"], new["topFunctions"]
    for name in sorted(set(old_funs) & set(new_funs)):
        a, b = old_funs[name], new_funs[name]
        if a["visibility"] == "private" and b["visibility"] == "private":
            continue
        if (a["params"], a["return"]) != (b["params"], b["return"]):
            cat = "inline" if (a["inline"] or b["inline"]) else (
                "suspend" if (a["suspend"] or b["suspend"]) else "signature")
            add(cat, name, f"signature changed: {a['params']} -> {b['params']}")
        elif a["visibility"] != b["visibility"]:
            add("signature", name, "visibility changed")

    if old.get("topOther", []) != new.get("topOther", []):
        add("class-shape", "<root>", "unclassified top-level declaration changed")

    for sig in sorted(set(old["bodies"]) & set(new["bodies"])):
        a, b = old["bodies"][sig], new["bodies"][sig]
        if a["hash"] != b["hash"]:
            kind = a["kind"] if a["kind"] == b["kind"] else "inline"
            add(kind, sig.split("|")[0], "body changed")

    # Stable, de-duplicated output.
    seen = set()
    out = []
    for r in reasons:
        key = (r["category"], r["symbol"], r["detail"])
        if key not in seen:
            seen.add(key)
            out.append(r)
    return out


def field_diff(a, b):
    pa = {(p["name"], p["type"], p["mutable"]) for p in a}
    pb = {(p["name"], p["type"], p["mutable"]) for p in b}
    added = sorted(n for n, _, _ in pb - pa)
    removed = sorted(n for n, _, _ in pa - pb)
    if added or removed:
        return f"fields added={added} removed={removed}"
    return "field order changed"


def resolve_tool(arg):
    tool = arg or os.environ.get("KLIB_TOOL")
    if not tool:
        dist = os.environ.get("KOTLIN_DIST")
        if dist:
            tool = os.path.join(dist, "bin", "klib")
    return tool


def cmd_snapshot(args):
    tool = resolve_tool(args.klib_tool)
    if not args.daemon and (not tool or not os.path.isfile(tool)):
        warn("snapshot: no klib tool (use --klib-tool or KOTLIN_DIST/KLIB_TOOL)")
        return 2
    data, _ = snapshot_cached(args.klib, tool, args.daemon, args.cache_dir)
    write_json(args.out, data)
    return 0


def parse_modules(values):
    mods = []
    for v in values:
        name, sep, path = v.partition("=")
        if not sep or not name:
            raise ValueError(f"--module expects <name>=<klib>, got {v!r}")
        mods.append((name, path))
    return mods


def now_ms():
    import time
    return int(time.time() * 1000)


def cmd_baseline(args):
    tool = resolve_tool(args.klib_tool)
    for name, klib in parse_modules(args.module):
        data, _ = snapshot_cached(klib, tool, args.daemon, args.cache_dir)
        write_json(os.path.join(args.baseline_dir, name + ".json"), data)
    return 0


def cmd_check(args):
    t_start = now_ms()
    tool = resolve_tool(args.klib_tool)
    prior = {}
    for v in args.prior_ms or []:
        name, _, ms = v.partition("=")
        prior[name] = int(ms)
    times, modes, all_reasons = {}, {}, []
    classify_ms = 0
    for name, klib in parse_modules(args.module):
        base_path = os.path.join(args.baseline_dir, name + ".json")
        new_path = os.path.join(args.baseline_dir, name + ".new.json")
        old = load(base_path)
        t0 = now_ms()
        if klib in ("", "-"):
            new, mode = old, "same"
        else:
            sha = sha256_file(klib)
            if old.get("klibSha256") == sha:
                new, mode = old, "same"
            else:
                new, mode = snapshot_cached(klib, tool, args.daemon, args.cache_dir)
        write_json(new_path, new)
        times[name] = now_ms() - t0 + prior.get(name, 0)
        modes[name] = mode
        t1 = now_ms()
        if mode != "same":
            all_reasons += reasons_for(old, new)
        classify_ms += now_ms() - t1
    if not all_reasons:
        print("HOT no incompatible change")
    for r in all_reasons:
        print(f"WARM {r['category']} {r['symbol']}: {r['detail']}")
    total = now_ms() - t_start + sum(prior.values())
    extra = " ".join(f"{n}={m}" for n, m in modes.items())
    print(f"compat: timing ms={total} ui_snapshot={times.get('ui', 0)} app_snapshot={times.get('app', 0)} "
          f"classify={classify_ms} {extra}")
    return 3 if all_reasons else 0


def cmd_classify(args):
    reasons = reasons_for(load(args.old), load(args.new))
    if args.json:
        json.dump({"verdict": "warm-restart" if reasons else "hot", "reasons": reasons},
                  sys.stdout, indent=1)
        sys.stdout.write("\n")
    else:
        if not reasons:
            print("HOT no incompatible change")
        for r in reasons:
            print(f"WARM {r['category']} {r['symbol']}: {r['detail']}")
    return 3 if reasons else 0


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    sp = sub.add_parser("snapshot")
    sp.add_argument("--klib", required=True)
    sp.add_argument("--out", required=True)
    sp.add_argument("--klib-tool")
    sp.add_argument("--daemon")
    sp.add_argument("--cache-dir")
    sp.set_defaults(func=cmd_snapshot)
    for name, func in (("baseline", cmd_baseline), ("check", cmd_check)):
        xp = sub.add_parser(name)
        xp.add_argument("--baseline-dir", required=True)
        xp.add_argument("--module", action="append", required=True)
        xp.add_argument("--klib-tool")
        xp.add_argument("--daemon")
        xp.add_argument("--cache-dir")
        if name == "check":
            xp.add_argument("--prior-ms", action="append")
        xp.set_defaults(func=func)
    cp = sub.add_parser("classify")
    cp.add_argument("--old", required=True)
    cp.add_argument("--new", required=True)
    cp.add_argument("--json", action="store_true")
    cp.set_defaults(func=cmd_classify)
    args = ap.parse_args(argv)
    try:
        return args.func(args)
    except (RuntimeError, OSError, ValueError) as e:
        warn(f"compat-classify: {e}")
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
