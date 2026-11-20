#!/usr/bin/env python3
"""Check that a klib set is closed: every `depends=` entry of every klib is the
`unique_name` of a klib in the set (or of the Kotlin dist), and no unique_name
appears twice.

  check-klib-deps.py [--external-klibs <json>] [--dist <KOTLIN_DIST>] <klib or dir> ...

Why: Kotlin/Native links a program even when a klib's dependency is missing
(partial linkage) and turns every use of the missing code into an
IrLinkageError at run time. That is how the toolchain shipped without
navigationevent-compose until .6: opening a Dialog terminated the app.

Inputs:
  <klib or dir>     a klib (unpacked directory with default/manifest, or a .klib
                    file), or a directory searched recursively for klibs
  --external-klibs  gen-external-klibs.py output; its entries carry "uniqueName"
                    and "depends" (recorded from the local file when pinned)
  --dist            Kotlin/Native dist; the unique names of its klib/common and
                    klib/platform/<target> klibs count as present. Without it,
                    "stdlib" and "org.jetbrains.kotlin.native.platform.*" do.
  --target          platform klib directory under --dist (default ios_simulator_arm64)

Exit 0 when closed, 1 when a dependency is missing or a unique_name is
duplicated (each one listed with the klibs that need it), 2 on a usage error.
"""
import argparse
import json
import os
import re
import sys
import zipfile


def manifest_text(path):
    if os.path.isdir(path):
        with open(os.path.join(path, "default", "manifest"), encoding="utf-8") as f:
            return f.read()
    with zipfile.ZipFile(path) as z:
        names = [n for n in z.namelist() if n.endswith("default/manifest")]
        if not names:
            raise ValueError(f"{path}: no default/manifest")
        return z.read(min(names, key=len)).decode("utf-8")


def parse(text):
    def prop(key):
        m = re.search(rf"^{key}=(.*)$", text, re.M)
        return m.group(1).replace("\\:", ":").strip() if m else ""
    return prop("unique_name"), [d for d in prop("depends").split() if d]


def is_klib(path):
    if os.path.isdir(path):
        return os.path.isfile(os.path.join(path, "default", "manifest"))
    return path.endswith(".klib") and os.path.isfile(path)


def find_klibs(root):
    if is_klib(root):
        return [root]
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        for d in list(dirnames):
            p = os.path.join(dirpath, d)
            if is_klib(p):
                found.append(p)
                dirnames.remove(d)  # do not descend into a klib
        found += [os.path.join(dirpath, f) for f in filenames if f.endswith(".klib")]
    return sorted(found)


def main(argv):
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--external-klibs")
    ap.add_argument("--dist")
    ap.add_argument("--target", default="ios_simulator_arm64")
    ap.add_argument("paths", nargs="*")
    try:
        a = ap.parse_args(argv)
    except SystemExit:
        return 2
    if not a.paths and not a.external_klibs:
        print(__doc__.strip().splitlines()[3], file=sys.stderr)
        return 2

    have = {}      # unique_name -> where it comes from
    dup = []       # (unique_name, first, second)
    needs = {}     # dependency -> set of klibs that need it

    def add(name, deps, origin):
        if name in have:
            dup.append((name, have[name], origin))
        else:
            have[name] = origin
        for d in deps:
            needs.setdefault(d, set()).add(name)

    for p in a.paths:
        if not os.path.exists(p):
            print(f"check-klib-deps: {p} does not exist", file=sys.stderr)
            return 2
        for k in find_klibs(p):
            name, deps = parse(manifest_text(k))
            add(name, deps, os.path.basename(k.rstrip("/")))

    if a.external_klibs:
        spec = json.load(open(a.external_klibs))
        for e in spec.get("externalKlibs", []) + spec.get("bundledKlibs", []):
            if "uniqueName" in e and "depends" in e:
                add(e["uniqueName"], e["depends"], e["coordinate"])
            elif e.get("source") and os.path.exists(e["source"]):
                name, deps = parse(manifest_text(e["source"]))
                add(name, deps, e["coordinate"])
            else:
                print(f"check-klib-deps: {e['coordinate']} has no uniqueName/depends; "
                      "regenerate the file with gen-external-klibs.py", file=sys.stderr)
                return 2

    provided = set()
    if a.dist:
        for sub in ("klib/common", f"klib/platform/{a.target}"):
            for k in find_klibs(os.path.join(a.dist, sub)):
                provided.add(parse(manifest_text(k))[0])
    else:
        provided.add("stdlib")

    def present(dep):
        if dep in have or dep in provided:
            return True
        return not a.dist and dep.startswith("org.jetbrains.kotlin.native.platform.")

    missing = sorted(d for d in needs if not present(d))
    for d in missing:
        print(f"MISSING {d} (needed by {', '.join(sorted(needs[d]))})")
    for name, first, second in dup:
        print(f"DUPLICATE {name}: {first} and {second}")
    if missing or dup:
        print(f"klib deps: {len(missing)} missing, {len(dup)} duplicated in {len(have)} klibs")
        return 1
    print(f"klib deps: closed ({len(have)} klibs, {len(needs)} distinct dependencies)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
