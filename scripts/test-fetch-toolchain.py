#!/usr/bin/env python3
"""Tests for fetch-toolchain.py download retries, error messages and the atomic, locked unpack.

  python3 scripts/test-fetch-toolchain.py

No network: urlopen is replaced by fakes, and the end-to-end case connects to
a closed local port. Exit 0 when all pass.
"""
import contextlib
import http.client
import importlib.util
import io
import os
import subprocess
import sys
import tempfile
import urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("fetch", os.path.join(HERE, "fetch-toolchain.py"))
fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch)

failed = 0


def check(name, cond, detail=""):
    global failed
    print(("ok   " if cond else "FAIL ") + name + ("" if cond else f": {detail}"))
    failed += 0 if cond else 1


class Resp(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def run(script, waits):
    """Fake urlopen following `script` (exceptions or bytes); record sleeps."""
    calls = {"n": 0}

    def fake(req, timeout=None):
        item = script[min(calls["n"], len(script) - 1)]
        calls["n"] += 1
        if isinstance(item, BaseException):
            raise item
        return Resp(item)

    fetch.urllib.request.urlopen = fake
    import time
    time.sleep = lambda s: waits.append(s)
    err = io.StringIO()
    code, data = None, None
    with contextlib.redirect_stderr(err):
        try:
            data = fetch.fetch_bytes("https://example.invalid/toolchain.json", attempts=4, backoff=1)
        except SystemExit as e:
            code = e.code
    return calls["n"], code, data, err.getvalue()


cut = lambda: http.client.IncompleteRead(b"x" * 20670, 1952)  # noqa: E731
http_err = lambda c: urllib.error.HTTPError("https://example.invalid", c, "err", {}, None)  # noqa: E731

waits = []
n, code, data, err = run([cut(), cut(), b'{"ok": 1}'], waits)
check("transfer cut short twice, then success", data == b'{"ok": 1}' and n == 3 and code is None, (n, code))
check("  exponential backoff 1 s, 2 s", waits == [1, 2], waits)
check("  bilingual retry notice", "重试" in err and "retrying" in err, err)

waits = []
n, code, data, err = run([cut()], waits)
check("always cut short: exit 5 after 4 attempts", code == 5 and n == 4, (n, code))
check("  waits 1, 2, 4 s", waits == [1, 2, 4], waits)
check("  short bilingual message, no traceback",
      "下载失败" in err and "download failed" in err and "Traceback" not in err, err)

waits = []
n, code, data, err = run([http_err(404)], waits)
check("HTTP 404 fails at once (no retry)", code == 5 and n == 1 and waits == [], (n, code, waits))

waits = []
n, code, data, err = run([http_err(503), b"{}"], waits)
check("HTTP 503 is retried", data == b"{}" and n == 2, (n, code))

with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
    f.write('{"truncated": ')
err = io.StringIO()
with contextlib.redirect_stderr(err):
    try:
        fetch.read_manifest(f.name)
        code = None
    except SystemExit as e:
        code = e.code
os.unlink(f.name)
check("invalid manifest JSON: exit 2 with a short message", code == 2 and "JSON" in err.getvalue(), err.getvalue())

with tempfile.TemporaryDirectory() as d:
    env = dict(os.environ, FETCH_RETRIES="3", FETCH_BACKOFF="0.01")
    r = subprocess.run([sys.executable, os.path.join(HERE, "fetch-toolchain.py"),
                        "https://127.0.0.1:9/toolchain.json", "--cache", d],
                       capture_output=True, text=True, env=env)
check("end to end: refused connection exits 5, no traceback",
      r.returncode == 5 and "Traceback" not in r.stderr and "download failed" in r.stderr,
      (r.returncode, r.stderr[-400:]))


# --- unpack: atomic, retried, locked (Issue #31) ---------------------------------------------------
import hashlib
import shutil
import stat
import textwrap


def make_bundle(d):
    """Three components; two share extractDir "kaldo" (like kaldo-iossim and kaldo-macos)."""
    comps = []
    for cid, xdir, files in [("kaldo-a", "kaldo", {"lib/libA.dylib": b"A" * 5000, "LICENSE": b"L"}),
                             ("kaldo-b", "kaldo", {"lib/libB.dylib": b"B" * 7000, "LICENSE": b"L"}),
                             ("orc", "orc-runtime", {"orc/liborc.a": b"O" * 3000})]:
        src = os.path.join(d, "src-" + cid)
        for rel, data in files.items():
            os.makedirs(os.path.dirname(os.path.join(src, rel)), exist_ok=True)
            with open(os.path.join(src, rel), "wb") as f:
                f.write(data)
            os.chmod(os.path.join(src, rel), 0o444)
        arc = os.path.join(d, cid + ".tar.zst")
        subprocess.run(f"tar -cf - -C '{src}' . | zstd -q -f -o '{arc}'", shell=True, check=True)
        data = open(arc, "rb").read()
        comps.append({"id": cid, "extractDir": xdir, "file": cid + ".tar.zst", "bytes": len(data),
                      "sha256": hashlib.sha256(data).hexdigest(),
                      "download": {"type": "release", "url": "file://" + arc}})
    return {"components": comps}


def fake_tar(d, body):
    bin_dir = os.path.join(d, "fakebin")
    os.makedirs(bin_dir, exist_ok=True)
    path = os.path.join(bin_dir, "tar")
    with open(path, "w") as f:
        f.write("#!/bin/bash\n" + textwrap.dedent(body))
    os.chmod(path, 0o755)
    return bin_dir


def run_components(m, root, path_prefix=None):
    err = io.StringIO()
    old = os.environ["PATH"]
    if path_prefix:
        os.environ["PATH"] = path_prefix + os.pathsep + old
    try:
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()) as out:
            try:
                with fetch.version_lock(root):
                    fetch.fetch_components(m, root)
                code = 0
            except SystemExit as e:
                code = e.code
    finally:
        os.environ["PATH"] = old
    return code, err.getvalue(), out.getvalue()


def tree_ok(root):
    def read(rel):
        p = os.path.join(root, rel)
        return open(p, "rb").read() if os.path.exists(p) else None
    return (read("kaldo/lib/libA.dylib") == b"A" * 5000 and read("kaldo/lib/libB.dylib") == b"B" * 7000
            and read("orc-runtime/orc/liborc.a") == b"O" * 3000)


def leftovers(root):
    return [n for n in os.listdir(root) if n.startswith(".extract-")]


if shutil.which("zstd") is None:
    print("skip unpack tests: zstd not on PATH")
else:
    with tempfile.TemporaryDirectory() as d:
        m = make_bundle(d)
        realtar = shutil.which("tar")
        once = os.path.join(d, "failed-once")
        fb = fake_tar(d, f"""
            if [ ! -e '{once}' ]; then : > '{once}'; cat > /dev/null; echo "tar: injected failure" >&2; exit 1; fi
            exec '{realtar}' "$@"
            """)
        root = os.path.join(d, "c1", "v")
        code, err, _ = run_components(m, root, fb)
        check("unpack fails once: retried, every component in place", code == 0 and tree_ok(root), (code, err))
        check("  retry is reported with the tar error", "retrying" in err and "injected failure" in err, err)
        check("  no temporary unpack directory left", leftovers(root) == [], leftovers(root))

        fb2 = fake_tar(d + "/always", """
            cat > /dev/null; echo "tar: injected failure" >&2; exit 1
            """)
        root = os.path.join(d, "c2", "v")
        code, err, _ = run_components(m, root, fb2)
        check("unpack always fails: exit 4 after 2 attempts with the tar error",
              code == 4 and "failed after 2 attempts" in err and "injected failure" in err, (code, err))
        check("  nothing marked done, no half-written files, archive kept",
              not os.path.exists(os.path.join(root, ".fetched", "kaldo-a"))
              and not os.path.exists(os.path.join(root, "kaldo", "lib"))
              and os.path.exists(os.path.join(root, ".download", "kaldo-a.tar.zst")) and leftovers(root) == [],
              sorted(os.listdir(root)))
        src_a = m["components"][0]["download"]["url"][len("file://"):]
        os.rename(src_a, src_a + ".gone")  # the rerun must reuse the kept archive
        code, err, out = run_components(m, root)
        os.rename(src_a + ".gone", src_a)
        check("  rerun reuses the verified archive and completes", code == 0 and tree_ok(root), (code, err))

        root = os.path.join(d, "c3", "v")
        os.makedirs(os.path.join(root, "kaldo", "lib"))
        stale = os.path.join(root, "kaldo", "lib", "libA.dylib")
        with open(stale, "wb") as f:
            f.write(b"half")
        os.chmod(stale, 0o444)
        os.chmod(os.path.join(root, "kaldo", "lib"), 0o555)
        os.makedirs(os.path.join(root, ".extract-kaldo-a-stale"))
        code, err, _ = run_components(m, root)
        check("leftovers of an interrupted run (read-only file, temp dir) are replaced",
              code == 0 and tree_ok(root) and leftovers(root) == [], (code, err))

        slow = fake_tar(d + "/slow", f"""
            sleep 0.4; exec '{realtar}' "$@"
            """)
        root = os.path.join(d, "c4", "v")
        mpath = os.path.join(d, "m.json")
        with open(mpath, "w") as f:
            json_text = __import__("json").dumps(m)
            f.write(json_text)
        prog = textwrap.dedent(f"""
            import importlib.util, json, sys
            spec = importlib.util.spec_from_file_location("fetch", {os.path.join(HERE, "fetch-toolchain.py")!r})
            fetch = importlib.util.module_from_spec(spec); spec.loader.exec_module(fetch)
            m = json.load(open({mpath!r}))
            with fetch.version_lock({root!r}):
                fetch.fetch_components(m, {root!r})
            """)
        env = dict(os.environ, PATH=slow + os.pathsep + os.environ["PATH"])
        procs = [subprocess.Popen([sys.executable, "-c", prog], env=env, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, text=True) for _ in range(2)]
        res = [p.communicate() + (p.returncode,) for p in procs]
        check("two fetches of the same version at once: both succeed, files intact",
              all(r[2] == 0 for r in res) and tree_ok(root), [(r[2], r[1][-300:]) for r in res])
        check("  the second one waited and found the components done",
              any("waiting" in r[1] for r in res) and sum(r[0].count("cached") for r in res) == 3,
              [r[0] for r in res])

print("all fetch tests passed" if failed == 0 else f"{failed} failed")
sys.exit(1 if failed else 0)
