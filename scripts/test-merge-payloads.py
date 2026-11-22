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

    # 5. Issue #91: names that several libraries define (three tiers), and the patch side (--patch-dups).
    t = tempfile.mkdtemp(prefix="merge-test-dups-")
    try:
        test_duplicates(t)
    finally:
        subprocess.run(["rm", "-rf", t])
    if fails == 0:
        print("all merge-payloads tests passed")
    return 1 if fails else 0


def fn(name, body):
    """A C function under a Kotlin-like symbol name."""
    c = "f_" + str(abs(hash(name)))
    return f'int {c}(void) __asm__("{name}");\nint {c}(void){{ {body} }}\n'


def cache_archive(root, lib, cache, source):
    """<root>/ic/<lib>-per-file-cache/<cache>/bin/lib<cache>.a with one member compiled from [source]."""
    d = os.path.join(root, "ic", f"{lib}-per-file-cache", cache, "bin")
    os.makedirs(d, exist_ok=True)
    obj = cc(d, f"lib{cache}.a", source)  # member name like the compiler's: lib<cache>.a.o
    a = os.path.join(d, f"lib{cache}.a")
    subprocess.run(["ar", "rcs", a, os.path.basename(obj)], cwd=d, check=True)
    os.remove(obj)
    return a


def ld_r(inputs, out):
    cmd = ["ld", "-r", "-arch", "arm64", "-platform_version", "macos", "13.0", "13.0", "-o", out]
    for p in inputs:
        cmd += ["-force_load", p] if p.endswith(".a") else [p]
    return subprocess.run(cmd, capture_output=True, text=True)


def nm_kinds(path, name):
    """['external' | 'non-external' | 'weak external', ...] of the definitions of [name] in [path]."""
    out = subprocess.run(["nm", "-m", path], capture_output=True, text=True).stdout
    kinds = []
    for line in out.splitlines():
        if line.endswith(" " + name) and "(undefined)" not in line:
            for k in ("weak external", "non-external", "external"):
                if k in line:
                    kinds.append(k)
                    break
    return kinds


def run_resolve(m, inputs, hot, work):
    import contextlib
    import io
    tables = {p: m.nm_table(p) for p in inputs}
    buf = io.StringIO()
    replaced = {}
    os.makedirs(work, exist_ok=True)
    with contextlib.redirect_stdout(buf):
        copies, refs = m.collect_copies(inputs, tables, {}, set())
        plan = m.plan_duplicates(copies, refs, set(hot), work)
        ok = m.report_duplicates(plan, "test")
        if ok:
            replaced = m.apply_duplicate_plan(plan, work)
    return (0 if ok else 1), replaced, plan, buf.getvalue()


def test_duplicates(t):
    m = load({})
    llvm = os.environ.get("KONAN_LLVM_HOME")
    if not llvm or not os.path.isfile(os.path.join(llvm, "bin", "llvm-objcopy")):
        print("FAIL #91 tests need KONAN_LLVM_HOME with bin/llvm-objcopy (the toolchain's llvm-tools)")
        global fails
        fails += 1
        return
    HINT, SAME, DIFF, DATA = "_kfun:hint#h(){}", "_kfun:same#s(){}", "_kfun:diff#d(){}", "_kvar:data#v"

    # tier 1: two libraries, different code, nobody uses it -> every copy local, nothing dropped
    a = cache_archive(t, "liba", "hints.aaa", fn(HINT, "return 1;"))
    b = cache_archive(t, "libb", "hints.aaa", fn(HINT, "return 2;"))
    code, replaced, plan, out = run_resolve(m, [a, b], [], os.path.join(t, "w1"))
    check("#91 tier 1: unreferenced, different code -> local", code == 0 and list(plan["local"]) == [HINT]
          and "tier 1 local=1" in out and "unreferenced" in out, out)
    merged = os.path.join(t, "t1.o")
    r = ld_r([replaced.get(a, a), replaced.get(b, b)], merged)
    check("#91 tier 1: ld -r links, both copies kept as local symbols", r.returncode == 0
          and nm_kinds(merged, HINT) == ["non-external", "non-external"], (r.stderr, nm_kinds(merged, HINT)))
    r0 = ld_r([a, b], os.path.join(t, "t1-before.o"))
    check("#91 control: the same archives without the rule stop on a duplicate symbol",
          r0.returncode != 0 and "duplicate symbol" in r0.stderr, r0.stderr[-300:])

    # tier 2: identical code, used by a third object -> one strong copy, the others weak
    c1 = cache_archive(t, "liba", "same.bbb", fn(SAME, "return 7;"))
    c2 = cache_archive(t, "libb", "same.bbb", fn(SAME, "return 7;"))
    user = cache_archive(t, "app", "main.ccc", f'extern int s(void) __asm__("{SAME}");\n' + fn("_kfun:app#u(){}", "return s();"))
    code, replaced, plan, out = run_resolve(m, [c1, c2, user], [], os.path.join(t, "w2"))
    check("#91 tier 2: used, byte-identical -> weak, basis printed", code == 0 and list(plan["weak"]) == [SAME]
          and "tier 2 weak=1" in out and "identical:" in out and "sha256" in out and "bytes" in out, out)
    merged = os.path.join(t, "t2.o")
    r = ld_r([replaced.get(p, p) for p in (c1, c2, user)], merged)
    check("#91 tier 2: ld -r links, one strong definition", r.returncode == 0 and nm_kinds(merged, SAME) == ["external"],
          (r.stderr, nm_kinds(merged, SAME)))

    # tier 3: different code and used -> explicit error naming the copies, nothing linked
    d1 = cache_archive(t, "liba", "diff.ddd", fn(DIFF, "return 1;"))
    d2 = cache_archive(t, "libb", "diff.ddd", fn(DIFF, "return 2;"))
    user3 = cache_archive(t, "app", "main3.eee", f'extern int d(void) __asm__("{DIFF}");\n' + fn("_kfun:app#u3(){}", "return d();"))
    code, replaced, plan, out = run_resolve(m, [d1, d2, user3], [], os.path.join(t, "w3"))
    check("#91 tier 3: used, different -> exit 1, names both libraries and caches, asks for a human decision",
          code == 1 and "tier 3 conflict=1" in out and "liba (cache diff.ddd)" in out and "libb (cache diff.ddd)" in out
          and "semantic conflict that needs a human decision" in out, out)

    # a hot symbol is never weakened or localized, even when the copies are identical
    code, replaced, plan, out = run_resolve(m, [c1, c2, user], [SAME], os.path.join(t, "w4"))
    check("#91 hot symbol: identical copies still a conflict (never weakened)", code == 1 and list(plan["conflict"]) == [SAME]
          and "hot symbol" in out, out)

    # a data symbol that is used cannot be compared byte for byte -> conflict
    v1 = cache_archive(t, "liba", "data.fff", f'int v1 __asm__("{DATA}") = 1;\n')
    v2 = cache_archive(t, "libb", "data.fff", f'int v2 __asm__("{DATA}") = 1;\n')
    userv = cache_archive(t, "app", "mainv.ggg", f'extern int v __asm__("{DATA}");\n' + fn("_kfun:app#uv(){}", "return v;"))
    code, replaced, plan, out = run_resolve(m, [v1, v2, userv], [], os.path.join(t, "w5"))
    check("#91 used data symbol -> conflict (not comparable code)", code == 1 and list(plan["conflict"]) == [DATA]
          and "not code" in out, out)

    # two files of ONE library -> not a tier: an error that points at the toolchain (distinct declarations)
    s1 = cache_archive(t, "libs", "pkg.one", fn("_kfun:pkg.module$1#invoke(){}", "return 1;"))
    s2 = cache_archive(t, "libs", "pkg.two", fn("_kfun:pkg.module$1#invoke(){}", "return 2;"))
    code, replaced, plan, out = run_resolve(m, [s1, s2], [], os.path.join(t, "w6"))
    check("#91 two files of one library -> error, nothing localized or weakened", code == 1
          and list(plan["same_library"]) == ["_kfun:pkg.module$1#invoke(){}"] and not plan["local"] and not plan["weak"]
          and "two files of one library" in out, out)

    # weak definitions never clash and are not counted
    w1 = cache_archive(t, "liba", "weak.hhh", '__attribute__((weak)) int wk(void) __asm__("_kfun:weak#w(){}");\nint wk(void){return 1;}\n')
    w2 = cache_archive(t, "libb", "weak.hhh", '__attribute__((weak)) int wk(void) __asm__("_kfun:weak#w(){}");\nint wk(void){return 2;}\n')
    code, replaced, plan, out = run_resolve(m, [w1, w2], [], os.path.join(t, "w7"))
    check("#91 weak definitions are not duplicates", code == 0 and not any(plan[k] for k in plan), out)

    # --patch-dups: a patch that uses a name the packaged app keeps local -> exit 3 with a WARM line
    record = os.path.join(t, "merged-bootstrap.dups.json")
    open(record, "w").write('{"local": ["%s"], "weak": []}' % HINT)
    hot = os.path.join(t, "hot-symbols.txt")
    open(hot, "w").write("_kfun:app#u(){}\n")
    pd = os.path.join(t, "patch")
    os.makedirs(pd)
    uses = cc(pd, "uses", f'extern int h(void) __asm__("{HINT}");\n' + fn("_kfun:core#caller(){}", "return h();"))
    r = subprocess.run([sys.executable, TOOL, "--patch-dups", record, hot, f"core={uses}"], capture_output=True, text=True)
    check("#91 patch uses a name kept local -> exit 3, WARM duplicate-declaration", r.returncode == 3
          and f"WARM duplicate-declaration {HINT}:" in r.stdout, (r.returncode, r.stdout, r.stderr))

    # --patch-dups: a patch that defines such a name (downstream file rebuilt) -> its copy becomes local too
    defines = cc(pd, "defines", fn(HINT, "return 3;"))
    r = subprocess.run([sys.executable, TOOL, "--patch-dups", record, hot, f"domain={defines}"], capture_output=True, text=True)
    check("#91 patch defines a name kept local -> exit 0, the copy is local", r.returncode == 0
          and nm_kinds(defines, HINT) == ["non-external"] and "1 definition(s)" in r.stdout, (r.returncode, r.stdout, nm_kinds(defines, HINT)))

    # --patch-dups: two modules of one patch define the same new name (e.g. both modules' hint files rebuilt)
    NEW = "_kfun:hint#fresh(){}"
    p1 = cc(pd, "p1", fn(NEW, "return 1;"))
    p2 = cc(pd, "p2", fn(NEW, "return 2;"))
    r0 = ld_r([p1, p2], os.path.join(pd, "before.o"))
    r = subprocess.run([sys.executable, TOOL, "--patch-dups", record, hot, f"core={p1}", f"domain={p2}"], capture_output=True, text=True)
    r1 = ld_r([p1, p2], os.path.join(pd, "after.o"))
    check("#91 patch with the same name from two modules: duplicate before, tier 1 local after, ld -r links",
          r0.returncode != 0 and r.returncode == 0 and "tier 1 local=1" in r.stdout and r1.returncode == 0
          and nm_kinds(os.path.join(pd, "after.o"), NEW) == ["non-external", "non-external"], (r.stdout, r1.stderr))
    q1 = cc(pd, "q1", fn("_kfun:hint#q(){}", "return 1;"))
    q2 = cc(pd, "q2", fn("_kfun:hint#q(){}", "return 2;"))
    q3 = cc(pd, "q3", 'extern int q(void) __asm__("_kfun:hint#q(){}");\n' + fn("_kfun:core#useq(){}", "return q();"))
    r = subprocess.run([sys.executable, TOOL, "--patch-dups", record, hot, f"core={q1}", f"domain={q2}", f"core={q3}"],
                       capture_output=True, text=True)
    check("#91 patch: two modules, different code, used inside the patch -> exit 1 with the conflict error",
          r.returncode == 1 and "semantic conflict that needs a human decision" in r.stdout, (r.returncode, r.stdout))


if __name__ == "__main__":
    sys.exit(main())
