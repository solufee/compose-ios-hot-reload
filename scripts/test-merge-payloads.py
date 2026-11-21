#!/usr/bin/env python3
"""Tests for the speed-ups in example-gradle/tools/merge-payloads.py (no simulator,
no Kotlin build; needs Xcode's clang, nm and ar).

  python3 scripts/test-merge-payloads.py

1. nm_global_lines (one nm per batch of files) returns exactly what one
   `nm -g <file>` per file returns, for a batch, a single file, a batch split
   in two (NM_BATCH), and a file nm cannot read.
2. collect_hot_symbols picks the user-module member and its _kfun: symbols.
3. The per-archive cache: the key changes with the archive bytes and with the
   flags; cache_put/cache_get round-trip; cache_prune removes only unused
   entries and leaves foreign files alone.
4. --rename-compact-unwind (Issue #80): __LD,__compact_unwind -> __KALDO,__kaldo_cu,
   a second run changes nothing, an object without the section is left alone.
"""
import importlib.util
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.environ.get("MERGE_PAYLOADS") or os.path.join(HERE, "..", "example-gradle", "tools", "merge-payloads.py")

fails = 0


def check(name, ok, detail=""):
    global fails
    print(("ok   " if ok else "FAIL ") + name + ("" if ok else f": {detail}"))
    if not ok:
        fails += 1


def load(env):
    old = {k: os.environ.get(k) for k in env}
    os.environ.update(env)
    try:
        spec = importlib.util.spec_from_file_location("merge_payloads", TOOL)
        m = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(m)
        return m
    finally:
        for k, v in old.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v


def cc(d, name, body):
    src = os.path.join(d, name + ".c")
    open(src, "w").write(body)
    obj = os.path.join(d, name + ".o")
    subprocess.run(["xcrun", "clang", "-c", "-arch", "arm64", src, "-o", obj], check=True)
    return obj


def main():
    t = tempfile.mkdtemp(prefix="merge-test-")
    try:
        objs = [cc(t, f"m{i}", f"int g{i}(void){{return {i};}}\nextern int u{i}(void);\n"
                              f"int h{i}(void){{return u{i}();}}\nstatic int s{i};\n") for i in range(5)]
        user = cc(t, "user", 'int __asm_main(void) __asm__("_kfun:#main(){}");\n'
                             'int __asm_main(void){return 1;}\n'
                             'int __asm_f(void) __asm__("_kfun:expui#f(){}");\n'
                             'int __asm_f(void){return 2;}\n')
        junk = os.path.join(t, "junk.o")
        open(junk, "wb").write(b"not an object")
        m = load({"KALDO_HOT_PACKAGES": "expui"})

        def per_file(p):
            r = subprocess.run(["nm", "-g", os.path.basename(p)], cwd=os.path.dirname(p),
                               capture_output=True, text=True)
            return r.stdout.splitlines()

        files = objs + [user, junk]
        got = m.nm_global_lines(files)
        same = all(got.get(p, []) == per_file(p) for p in files)
        check("batched nm == per-file nm (7 files, one unreadable)", same,
              {os.path.basename(p): (got.get(p), per_file(p)) for p in files if got.get(p, []) != per_file(p)})
        check("unreadable file gets no lines", junk not in got or got[junk] == [], got.get(junk))
        one = m.nm_global_lines([objs[0]])
        check("single-file batch (nm prints no header)", one.get(objs[0]) == per_file(objs[0]), one)
        m.NM_BATCH = 2
        split = m.nm_global_lines(files)
        check("batches of 2 give the same result", split == got)
        m.NM_BATCH = 400

        hot = m.collect_hot_symbols(objs + [user])
        check("hot symbols come from the user module only",
              hot == ["_kfun:#main(){}", "_kfun:expui#f(){}"], hot)
        check("no user module -> no hot symbols", m.collect_hot_symbols(objs) == [])

        cache = os.path.join(t, "cache")
        m.MERGE_CACHE = cache
        k1 = m.cache_key(b"archive-1")
        check("key depends on the archive bytes", k1 != m.cache_key(b"archive-2"))
        os.environ["KALDO_GUEST_DROP_CXX"] = "1"  # read when the key is made, like dedup_archive
        try:
            k_flag = m.cache_key(b"archive-1")
        finally:
            del os.environ["KALDO_GUEST_DROP_CXX"]
        m2 = load({"KALDO_HOT_PACKAGES": "expui,other"})
        m2.MERGE_CACHE = cache
        check("key depends on the flags and hot packages",
              len({k1, k_flag, m2.cache_key(b"archive-1")}) == 3)
        check("miss on an empty cache", m.cache_get(k1) is None)
        a = os.path.join(t, "d-p01.a")
        open(a, "wb").write(b"!<arch>\nprocessed")
        path = m.cache_put(k1, a, {"hot": ["_kfun:x"], "weakened": 1})
        hit = m.cache_get(k1)
        check("put then get round-trips", hit is not None and hit[0] == path
              and open(path, "rb").read() == b"!<arch>\nprocessed"
              and hit[1] == {"hot": ["_kfun:x"], "weakened": 1}, hit)
        k2 = m.cache_key(b"archive-2")
        b = os.path.join(t, "d-p02.a")
        open(b, "wb").write(b"!<arch>\nother")
        m.cache_put(k2, b, {"hot": [], "weakened": 0})
        open(os.path.join(cache, "README.txt"), "w").write("not ours")
        os.remove(os.path.join(cache, k2 + ".json"))
        check("entry without its .json is a miss", m.cache_get(k2) is None)
        m.cache_prune({k1})
        left = sorted(os.listdir(cache))
        check("prune keeps used entries and foreign files only",
              left == sorted([k1 + ".a", k1 + ".json", "README.txt"]), left)
    finally:
        subprocess.run(["rm", "-rf", t])

    # 4. Issue #80: --rename-compact-unwind on a patch object (idempotent; no section = no change).
    t = tempfile.mkdtemp(prefix="merge-test-cu-")
    try:
        m = load({})
        obj = cc(t, "patch", "extern void ext(void);\nint f(int x){ if (x) ext(); return x + 1; }\n")

        def sections(p):
            r = subprocess.run(["otool", "-l", p], capture_output=True, text=True).stdout.split("\n")
            return [(r[i].split()[-1], r[i + 1].split()[-1]) for i in range(len(r) - 1)
                    if r[i].strip().startswith("sectname")]

        before = sections(obj)
        check("clang object has __LD,__compact_unwind", ("__compact_unwind", "__LD") in before, before)
        r1 = subprocess.run([sys.executable, TOOL, "--rename-compact-unwind", obj], capture_output=True, text=True)
        after = sections(obj)
        check("renamed to __KALDO,__kaldo_cu, exit 0", r1.returncode == 0 and ("__kaldo_cu", "__KALDO") in after
              and ("__compact_unwind", "__LD") not in after, (r1.returncode, r1.stdout, after))
        data1 = open(obj, "rb").read()
        r2 = subprocess.run([sys.executable, TOOL, "--rename-compact-unwind", obj], capture_output=True, text=True)
        check("second run: exit 0, object unchanged (idempotent)", r2.returncode == 0 and open(obj, "rb").read() == data1,
              (r2.returncode, r2.stdout))
        plain = cc(t, "nocu", "int g;\n")
        data2 = open(plain, "rb").read()
        check("object without compact unwind: rename_compact_unwind False, unchanged",
              m.rename_compact_unwind(plain) is False and open(plain, "rb").read() == data2)
    finally:
        subprocess.run(["rm", "-rf", t])
    if fails == 0:
        print("all merge-payloads tests passed")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
