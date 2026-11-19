#!/usr/bin/env python3
"""4 edit types x 10 hot reloads with real taps already done by the caller.

For each round: edit main.kt, run reload-a.sh, poll the console log for
`RENDER <tag> page=2 ... count=3`, verify the PID, record save->frame latency.
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
MAIN = os.path.join(HERE, "main.kt")
LOG = os.path.join(HERE, sys.argv[1] if len(sys.argv) > 1 else "launch-acc3.log")
ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 10

ENV = dict(os.environ)
ENV.update({
    "JAVA_HOME": os.path.expanduser(
        "~/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.21+9/Contents/Home"),
    "KLIBLIST": os.path.join(HERE, "klibs-jbmain4.txt"),
    "AC_FROM": os.path.join(HERE, "empty-auto-cache"),
    "AUTO_CACHE": os.path.join(HERE, "auto-cache-jbmain27"),
    "IC_DIR": os.path.join(HERE, "ic-jbmain27"),
})


def pid():
    out = subprocess.run(["pgrep", "-f", "ComposeHR.app/ComposeHR"],
                         capture_output=True, text=True).stdout.split()
    return out[0] if out else None


def set_tag(tag):
    s = open(MAIN).read()
    s2 = re.sub(r'private const val BUILD_TAG = "[^"]*"',
                f'private const val BUILD_TAG = "{tag}"', s)
    assert s2 != s, "tag edit failed"
    open(MAIN, "w").write(s2)


def toggle_color(state):
    s = open(MAIN).read()
    new = "Color(0xFF66D9FF)" if state else "Color(0xFFFFD166)"
    s2 = re.sub(r"accent\(\): Color = Color\(0x[0-9A-Fa-f]+\)",
                f"accent(): Color = {new}", s)
    assert s2 != s, "color edit failed"
    open(MAIN, "w").write(s2)


def toggle_extra_text(add):
    s = open(MAIN).read()
    marker = '        Text("hot", color = accent(), fontSize = 18.sp)\n'
    if add:
        anchor = '        Text("Page 2", color = Color.White, fontSize = 32.sp)\n'
        assert anchor in s
        s2 = s.replace(anchor, anchor + marker)
    else:
        assert marker in s
        s2 = s.replace(marker, "")
    assert s2 != s, "extra text edit failed"
    open(MAIN, "w").write(s2)


def toggle_if_branch(use_if):
    s = open(MAIN).read()
    plain = 'Text("Counter: $count", color = Color.White, fontSize = 40.sp)'
    branch = ('if (count > 0) Text("Counter: $count", color = Color.White, fontSize = 40.sp) '
              'else Text("Counter: 0", color = Color.White, fontSize = 40.sp)')
    if branch in s:
        s2 = s.replace(branch, plain)
    else:
        s2 = s.replace(plain, branch)
    assert s2 != s, "if edit failed"
    open(MAIN, "w").write(s2)


rounds = [
    ("text", set_tag),
    ("color", toggle_color),
    ("add", toggle_extra_text),
    ("if", toggle_if_branch),
]

results = {}
pid0 = pid()
print("pid0", pid0, flush=True)
tag_no = int(os.environ.get("START_TAG", "400"))
for name, fn in rounds:
    lat = []
    ok = 0
    for i in range(ROUNDS):
        tag_no += 1
        tag = f"v{tag_no}"
        set_tag(tag)
        if name == "color":
            toggle_color(i % 2 == 0)
        elif name == "add":
            toggle_extra_text(i % 2 == 0)
        elif name == "if":
            toggle_if_branch(i % 2 == 0)

        t0 = time.time()
        r = subprocess.run([os.path.join(HERE, os.environ.get("RELOAD", "reload-a.sh"))], env=ENV,
                           capture_output=True, text=True)
        sent = time.time()
        found = None
        line = None
        while time.time() - t0 < 120:
            with open(LOG, "rb") as fh:
                size = os.path.getsize(LOG)
                fh.seek(max(0, size - 200000))
                d = fh.read().replace(b"\r", b"\n").decode("utf-8", "replace")
            for l in reversed(d.splitlines()):
                if l.startswith(f"RENDER {tag} page=2") and "count=3" in l:
                    found = time.time()
                    line = l.strip()
                    break
            if found:
                break
            time.sleep(0.05)
        p = pid()
        good = bool(found) and p and p == pid0
        if good:
            ok += 1
            lat.append((found - t0) * 1000)
        print(f"{name}[{i}] {'OK' if good else 'FAIL'} total={int(((found or time.time())-t0)*1000)}ms "
              f"send={int(((found or time.time())-sent)*1000)}ms pid={p} line={line}", flush=True)
    results[name] = (ok, lat)

print("=== summary ===", flush=True)
allv = []
for name, (ok, lat) in results.items():
    lat_s = sorted(lat)
    def pct(v, p):
        if not v:
            return 0
        k = (len(v) - 1) * p
        lo = int(k)
        hi = min(lo + 1, len(v) - 1)
        return v[lo] + (v[hi] - v[lo]) * (k - lo)
    print(f"{name}: ok={ok}/{ROUNDS} p50={pct(lat_s,.5):.0f}ms p90={pct(lat_s,.9):.0f}ms", flush=True)
    allv += lat
al = sorted(allv)
def pct(v, p):
    if not v:
        return 0
    k = (len(v) - 1) * p
    lo = int(k)
    hi = min(lo + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)
print(f"ALL: ok={sum(1 for ok,_ in results.values() for _ in range(0)) if False else sum(ok for ok,_ in results.values())}"
      f"/{ROUNDS*4} p50={pct(al,.5):.0f}ms p90={pct(al,.9):.0f}ms", flush=True)
