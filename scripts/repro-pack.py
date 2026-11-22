#!/usr/bin/env python3
"""Reproducible toolchain archives (Issue #96).

  repro-pack.py tar DIR                 write a deterministic tar of DIR to stdout
  repro-pack.py digest DIR              content digest of DIR
  repro-pack.py digest-archive A.tar.zst  content digest of an existing archive (any tar header values)
  repro-pack.py raw-bytes DIR           unpacked size estimate (sum of file sizes in 4 KiB blocks)

The tar stream depends only on the files: entries in byte order of their path (a directory before its contents,
"./" prefix as `tar -C DIR -cf - .` writes), every mtime = SOURCE_DATE_EPOCH, uid/gid 0 with empty user and group
names, mode 0755 for directories and files with any execute bit and 0644 for other files, symlinks kept, no extended
attributes, PAX headers only for fields that ustar cannot hold. `zstd -19 -T0` of a piped stream is deterministic for
a given zstd version (checked on the .11 and .11.1 archives: recompressing each tar gave the published bytes), so the
archive's sha256 is a function of the files.

The content digest covers what extraction produces apart from times and owners: the path, the type, the execute bit,
the file bytes and the link target. Two archives with the same digest extract to the same files, so the packer can
reuse a previous release's archive byte for byte when a component did not change.
"""
import hashlib
import os
import stat
import subprocess
import sys
import tarfile


def entries(root):
    """(relative path, absolute path) of every entry under root, root first, in byte order of the path."""
    out = []

    def walk(rel, path):
        out.append((rel, path))
        if os.path.isdir(path) and not os.path.islink(path):
            for name in sorted(os.listdir(path), key=os.fsencode):
                walk(f"{rel}/{name}", os.path.join(path, name))

    walk(".", root)
    return out


def norm_mode(st):
    if stat.S_ISDIR(st.st_mode) or (stat.S_ISREG(st.st_mode) and st.st_mode & 0o111):
        return 0o755
    return 0o644


def source_date_epoch():
    v = os.environ.get("SOURCE_DATE_EPOCH")
    if not v or not v.isdigit():
        sys.exit("repro-pack: SOURCE_DATE_EPOCH (seconds) is required")
    return int(v)


def write_tar(root):
    mtime = source_date_epoch()
    out = sys.stdout.buffer
    with tarfile.open(fileobj=out, mode="w|", format=tarfile.PAX_FORMAT) as tf:
        for rel, path in entries(root):
            st = os.lstat(path)
            ti = tarfile.TarInfo("./" if rel == "." else rel)
            ti.mtime, ti.uid, ti.gid, ti.uname, ti.gname = mtime, 0, 0, "", ""
            if stat.S_ISLNK(st.st_mode):
                ti.type, ti.linkname, ti.mode = tarfile.SYMTYPE, os.readlink(path), 0o777
                tf.addfile(ti)
            elif stat.S_ISDIR(st.st_mode):
                ti.type, ti.mode = tarfile.DIRTYPE, 0o755
                tf.addfile(ti)
            elif stat.S_ISREG(st.st_mode):
                ti.type, ti.mode, ti.size = tarfile.REGTYPE, norm_mode(st), st.st_size
                with open(path, "rb") as f:
                    tf.addfile(ti, f)
            else:
                sys.exit(f"repro-pack: {rel}: unsupported file type")


def file_sha(f):
    h = hashlib.sha256()
    for block in iter(lambda: f.read(1 << 20), b""):
        h.update(block)
    return h.hexdigest()


def digest_lines_dir(root):
    for rel, path in entries(root):
        if rel == ".":
            continue
        st = os.lstat(path)
        rel = rel[2:]
        if stat.S_ISLNK(st.st_mode):
            yield f"l {rel} -> {os.readlink(path)}"
        elif stat.S_ISDIR(st.st_mode):
            yield f"d {rel}"
        else:
            with open(path, "rb") as f:
                yield f"f {rel} {'x' if st.st_mode & 0o111 else '-'} {file_sha(f)}"


def digest_lines_archive(archive):
    z = subprocess.Popen(["zstd", "-dcq", archive], stdout=subprocess.PIPE)
    lines = []
    with tarfile.open(fileobj=z.stdout, mode="r|") as tf:
        for ti in tf:
            rel = ti.name[2:] if ti.name.startswith("./") else ti.name
            rel = rel.rstrip("/")
            if rel in ("", "."):
                continue
            if ti.issym():
                lines.append(f"l {rel} -> {ti.linkname}")
            elif ti.isdir():
                lines.append(f"d {rel}")
            elif ti.isfile():
                lines.append(f"f {rel} {'x' if ti.mode & 0o111 else '-'} {file_sha(tf.extractfile(ti))}")
            else:
                lines.append(f"? {rel} type {ti.type!r}")
    if z.wait() != 0:
        sys.exit(f"repro-pack: zstd -d {archive} failed")
    return sorted(lines, key=lambda s: os.fsencode(s.split(" ", 2)[1]))


def digest(lines):
    h = hashlib.sha256()
    n = 0
    for line in lines:
        h.update(line.encode() + b"\n")
        n += 1
    return f"{h.hexdigest()} {n}"


def raw_bytes(root):
    total = 0
    for _, path in entries(root):
        st = os.lstat(path)
        total += 4096 if not stat.S_ISREG(st.st_mode) else (st.st_size + 4095) // 4096 * 4096
    return total


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    cmd, arg = sys.argv[1:]
    if cmd == "tar":
        write_tar(arg)
    elif cmd == "digest":
        lines = sorted(digest_lines_dir(arg), key=lambda s: os.fsencode(s.split(" ", 2)[1]))
        print(digest(lines))
    elif cmd == "digest-archive":
        print(digest(digest_lines_archive(arg)))
    elif cmd == "raw-bytes":
        print(raw_bytes(arg))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
