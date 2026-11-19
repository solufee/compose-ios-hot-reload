#!/usr/bin/env python3
"""Print and verify the klibs that a split build is about to link.

Fails when a klib is missing or two different files claim the same module
(the stale 1.10.0 `ui` cache cost a full debugging day).
Usage: verify-klibs.py <klib-list> [signature-out]
"""
import hashlib
import os
import subprocess
import sys
import zipfile


def manifest_text(path):
    if os.path.isdir(path):
        for cand in ("default/manifest", "manifest"):
            p = os.path.join(path, cand)
            if os.path.isfile(p):
                return open(p, "rb").read().decode("utf-8", "replace")
        return ""
    if path.endswith(".klib") and os.path.isfile(path):
        try:
            with zipfile.ZipFile(path) as z:
                return z.read("default/manifest").decode("utf-8", "replace")
        except Exception:
            return ""
    return ""


def field(text, key):
    for line in text.splitlines():
        if line.startswith(key + "="):
            return line[len(key) + 1:]
    return "?"


def md5_file(path):
    if not os.path.isfile(path):
        return "-"
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    klist = sys.argv[1]
    sig_path = sys.argv[2] if len(sys.argv) > 2 else None
    seen = {}
    total = missing = dup = 0
    rows = []
    for line in open(klist):
        k = line.strip()
        if not k:
            continue
        total += 1
        if not os.path.exists(k):
            print(f"KLIB-MISSING {k}", file=sys.stderr)
            missing += 1
            continue
        m = manifest_text(k)
        name = field(m, "short_name")
        ver = field(m, "compiler_version")
        uniq = field(m, "unique_name")
        md5 = md5_file(k)
        print(f"KLIB {name:26} {ver:24} {uniq:40} {md5} {k}")
        rows.append((uniq, ver, md5, name, k))
        if uniq != "?":
            if uniq in seen and seen[uniq] != k:
                print(f"KLIB-DUPLICATE module={uniq}", file=sys.stderr)
                print(f"  first: {seen[uniq]}", file=sys.stderr)
                print(f"  again: {k}", file=sys.stderr)
                dup += 1
            seen[uniq] = k
    if sig_path:
        with open(sig_path, "w") as f:
            for r in rows:
                f.write("\t".join(r) + "\n")
    print(f"KLIB-SUMMARY total={total} missing={missing} duplicate_modules={dup}")
    return 1 if (missing or dup) else 0


if __name__ == "__main__":
    sys.exit(main())
