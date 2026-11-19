#!/usr/bin/env python3
"""Tests for fetch-toolchain.py download retries and error messages.

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

print("all fetch tests passed" if failed == 0 else f"{failed} failed")
sys.exit(1 if failed else 0)
