#!/usr/bin/env python3
"""Scan files for things that must never be published, binaries included.

  scan-binary-leaks.py <dir> [<dir> ...]

Every file is read as bytes. Zip archives (jars, zipped klibs) are opened and
each member is scanned too, recursively, so compressed content cannot hide a
leak. Checks:
  - macOS and Linux home paths ("/Us" "ers/", "/home/") and this machine's home dir
  - "agent-" followed by 8 hex digits
  - the lines of the local deny file between "# binary-scan: begin" and
    "# binary-scan: end" (names, host names and internal names that are safe to
    look for in binaries, case-insensitive); the deny
    file is $EXPORT_DENY_FILE or ~/.config/compose-ios-hot-reload/export-deny.txt
Hits are reported per file (and zip member) and category; matched names are
never printed. Exit 1 on any hit, 2 on a usage error.

ALLOW lists exact exemptions for unmodified third-party files: (path glob,
category, regex that EVERY match must satisfy). The skiko klib redistributed
in bundled-klibs is JetBrains' published artifact, byte-identical to the one in
their Maven repository; its IR names JetBrains' CI build agent paths. A match
that does not satisfy the regex still fails, and every exemption is reported.
"""
import io
import os
import re
import sys
import zipfile

USAGE = "usage: scan-binary-leaks.py <dir> [<dir> ...]"

ALLOW = [
    ("*org.jetbrains.skiko/skiko-iossimulatorarm64/0.157.0-alpha01/skiko-iosSimulatorArm64Main-0.157.0-alpha01.klib",
     "macOS home path", rb"/Us" rb"ers/admin/buildAgent/work/[0-9a-f]{16}/skiko/"),
]


def deny_patterns():
    path = os.environ.get("EXPORT_DENY_FILE") or os.path.expanduser(
        "~/.config/compose-ios-hot-reload/export-deny.txt")
    if not os.path.isfile(path):
        sys.exit(f"scan: deny file {path} not found (set EXPORT_DENY_FILE)")
    out, on = [], False
    for line in open(path, encoding="utf-8").read().splitlines():
        if line.startswith("# binary-scan: begin"):
            on = True
            continue
        if line.startswith("# binary-scan: end"):
            on = False
            continue
        if on and line.strip() and not line.startswith("#"):
            out.append(line)
    if not out:
        sys.exit(f"scan: no binary-scan entries in {path}")
    return out


def checks():
    c = [("macOS home path", re.compile(re.escape(b"/Us" + b"ers/"))),
         ("Linux home path", re.compile(re.escape(b"/home/")))]
    home = os.path.expanduser("~")
    if home and home != "/":
        c.append(("home dir", re.compile(re.escape(home.encode()))))
    c.append(("agent id", re.compile(rb"agent-[0-9a-f]{8}")))
    for i, p in enumerate(deny_patterns(), 1):
        c.append((f"deny #{i}", re.compile(p.encode("utf-8"), re.I)))
    return c


def allowed(where, label, data, rx):
    import fnmatch
    for glob, lab, ok in ALLOW:
        # the archive itself (members may be stored uncompressed) and its members
        if lab == label and (fnmatch.fnmatch(where, glob) or fnmatch.fnmatch(where, glob + "!*")):
            okrx = re.compile(ok)
            return all(okrx.match(data, m.start()) for m in rx.finditer(data))
    return False


def scan_bytes(data, where, cs, hits, depth=0, exempt=None):
    for label, rx in cs:
        n = len(rx.findall(data))
        if n:
            if exempt is not None and allowed(where, label, data, rx):
                exempt.append((where, label, n))
            else:
                hits.append((where, label, n))
    if depth < 3 and data[:4] == b"PK\x03\x04":
        try:
            z = zipfile.ZipFile(io.BytesIO(data))
        except zipfile.BadZipFile:
            return
        for info in z.infolist():
            if info.is_dir():
                continue
            scan_bytes(z.read(info), f"{where}!{info.filename}", cs, hits, depth + 1, exempt)


def main(argv):
    if not argv or any(a.startswith("-") for a in argv):
        print(USAGE, file=sys.stderr)
        return 2
    for d in argv:
        if not os.path.isdir(d):
            print(f"scan: not a directory: {d}", file=sys.stderr)
            return 2
    cs = checks()
    hits, exempt, nfiles = [], [], 0
    for d in argv:
        for root, _, files in os.walk(d):
            for f in files:
                p = os.path.join(root, f)
                if os.path.islink(p):
                    continue
                nfiles += 1
                with open(p, "rb") as fh:
                    scan_bytes(fh.read(), os.path.relpath(p, d), cs, hits, 0, exempt)
    for where, label, n in exempt:
        print(f"exempt [{label}] {where} ({n}x, all matches are the allowed upstream path)")
    for where, label, n in hits:
        print(f"LEAK [{label}] {where} ({n}x)")
    if not hits:
        print(f"scan: clean ({nfiles} files, {len(cs)} patterns, zip members included)")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
