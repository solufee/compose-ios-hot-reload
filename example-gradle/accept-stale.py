#!/usr/bin/env python3
"""Forced recomposition after hot reload (toolchain .6): screens, state and timing.

  source <toolchain>/env.sh; [UDID=… RELOADS=10] python3 accept-stale.py [--skip-timing]

Uses the test app in accept-stale/ (copied to build/regression/stale/src, so the
tracked sources are never edited). Every screen shows S_MARK through call sites whose
arguments are static; Compose skips those unless the reload forces a recomposition.

Part 1, correctness (KONAN_HR_FORCE_RECOMPOSE on, the default). After real taps that
set plain remember state (Plain+ x3), rememberSaveable state (Saved+ x2) and the page:
  home    reload on the home page            -> HEADER/HOME show the new mark
  list    LazyColumn, scrolled down and back -> visible rows show the new mark
  popup   Popup open (separate layer)        -> popup and the page behind it update
  dialog  Dialog open (separate layer)       -> dialog and the page behind it update
  nav     page switches after the reloads    -> Home and List show the newest mark
  back    page with a BackHandler            -> page updates; a back event runs the
                                                reloaded handler (backs=1): a left-edge swipe,
                                                or SimBack (DirectNavigationEventInput) when
                                                the synthetic swipe is not recognised
  big     600-row LazyColumn, 14 nodes a row -> visible rows show the new mark
Each step checks: reload exit 0, same PID, no crash text, the new mark in the SHOW
lines, an "HR forceRecompose" line, and the state line still plain=3 saved=2 with the
expected page, popup and dialog flags. A screenshot is saved per step.

Part 2, timing: the app is launched with forced recomposition on and then off
(KONAN_HR_FORCE_RECOMPOSE=0); per mode RELOADS reloads on the List page and RELOADS on
the Big page. Per reload: ui klib build ms, reload ms (send -> ack), and with it on the
runtime's line (scopes invalidated/recomposed, reload success -> frame applied ms) and
send -> frame applied ms. Off mode shows the old behaviour: the marks stay stale.

Output: build/regression/stale/ (step logs, screenshots, timing.csv, summary.txt).
"""
import csv
import importlib.util
import os
import re
import shutil
import statistics
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
os.chdir(HERE)
UDID = os.environ.get("UDID", "0B879832-98D9-49A5-AC00-8BAB579AFBB2")
RELOADS = int(os.environ.get("RELOADS", "10"))
OUT = os.path.join(HERE, "build/regression/stale")
SRC = os.path.join(OUT, "src")
UI = os.path.join(SRC, "ui/stale.kt")
LOG = os.path.join(HERE, "launch.log")
CRASH_RE = re.compile(r"SIGSEGV|EXC_BAD|Uncaught|Terminating app|Hot-Reload failed|HOOK class=")
FORCE_RE = re.compile(r"HR forceRecompose recomposers=(\d+) compositions=(\d+) invalidated=(\d+) "
                      r"recomposed=(\d+) deferred=(\d+) dispatchMs=(\d+) forceMs=(\d+) "
                      r"frameMs=(\w+) totalMs=(\d+) frameEpochMs=(\d+)")
STATE_RE = re.compile(r"STALE_STATE page=(\d+) plain=(\d+) saved=(\d+) popup=(\w+) dialog=(\w+) backs=(\d+)")

spec = importlib.util.spec_from_file_location("accept_b4", os.path.join(HERE, "accept-b4.py"))
b4 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b4)
SCREEN = None

ENV = dict(os.environ)
ENV["APP_SRC"] = os.path.join(SRC, "app/main.kt")
ENV["UI_SRC_DIR"] = os.path.join(SRC, "ui")

results = []
mark_n = 0


def now_ms():
    return int(time.time() * 1000)


def sh(args, out_path=None, env=None):
    t0 = now_ms()
    r = subprocess.run(args, capture_output=True, text=True, env=env or ENV)
    if out_path:
        with open(out_path, "a") as f:
            f.write(f"$ {' '.join(args)}\n{r.stdout}{r.stderr}")
    return r.returncode, r.stdout + r.stderr, now_ms() - t0


def app_pid():
    r = subprocess.run(["pgrep", "-n", "-f", f"Devices/{UDID}/.*ComposeHR\\.app/ComposeHR"],
                       capture_output=True, text=True)
    return r.stdout.strip()


def log_lines():
    with open(LOG, errors="replace") as f:
        return [l.rstrip("\r\n").replace("\r", "") for l in f]


def wait_for(start, pred, timeout=8.0):
    """First line after index `start` for which pred(line) is true, else None."""
    end = time.time() + timeout
    while time.time() < end:
        for l in log_lines()[start:]:
            if pred(l):
                return l
        time.sleep(0.1)
    return None


def last_state():
    st = [m.groups() for m in map(STATE_RE.search, log_lines()) if m]
    return st[-1] if st else None


def set_mark():
    global mark_n
    mark_n += 1
    s = open(UI).read()
    s = re.sub(r'const val S_MARK = "[^"]*"', f'const val S_MARK = "s{mark_n}"', s)
    open(UI, "w").write(s)
    return f"s{mark_n}"


def reload(label):
    """ui klib + hot reload; returns (rc, klib_ms, reload_ms, t_send_ms)."""
    out = os.path.join(OUT, f"{label}.out")
    rc, _, klib_ms = sh(["bash", "hotreload.sh", "ui-klib"], out)
    if rc != 0:
        return rc, klib_ms, 0, now_ms()
    t_send = now_ms()
    rc, txt, reload_ms = sh(["bash", "hotreload.sh", "reload", "--no-warm"], out)
    if re.search(r"^WARM", txt, re.M):
        rc = rc or 99
    return rc, klib_ms, reload_ms, t_send


def screenshot(name):
    subprocess.run(["xcrun", "simctl", "io", UDID, "screenshot", os.path.join(OUT, name)],
                   capture_output=True)


def tap(label, expect=None, timeout=6.0):
    start = len(log_lines())
    b4.tap_label(label, SCREEN)
    if expect:
        line = wait_for(start, lambda l: re.search(expect, l) is not None, timeout)
        if line is None:
            raise RuntimeError(f"tap {label}: no log line matching {expect!r}")
    time.sleep(0.6)


def launch(force):
    subprocess.run(["pkill", "-f", os.path.join(HERE, "run.sh")], capture_output=True)
    subprocess.run(["xcrun", "simctl", "terminate", UDID, "com.example.composehr"], capture_output=True)
    open(LOG, "w").close()
    env = dict(ENV)
    env["KONAN_HR_FORCE_RECOMPOSE"] = "1" if force else "0"
    subprocess.Popen(["nohup", "bash", os.path.join(HERE, "run.sh")], env=env,
                     stdout=open(os.path.join(OUT, f"run-{'on' if force else 'off'}.out"), "w"),
                     stderr=subprocess.STDOUT, start_new_session=True)
    if wait_for(0, lambda l: l.startswith("STALE_STATE"), 90) is None:
        raise RuntimeError("launch: no STALE_STATE line")
    time.sleep(2)
    want = "HR forceRecompose: on" if force else "HR forceRecompose: off"
    if not any(want in l for l in log_lines()):
        raise RuntimeError(f"launch: missing {want!r}")
    return app_pid()


def record(name, ok, detail):
    results.append((name, ok, detail))
    print(f"{name} {'PASS' if ok else 'FAIL'} {detail}", flush=True)


def check_step(label, pid0, page, popup, needles, dialog="false", backs="0"):
    """Edit the mark, reload, check screen lines, force line, state, PID, crashes.

    A needle "NAME" needs a "SHOW NAME <mark>" line; "ROW*" needs at least 3 rows
    with the new mark and no row line with another mark after the reload."""
    mark = set_mark()
    start = len(log_lines())
    rc, klib_ms, reload_ms, _ = reload(label)
    want = [f"SHOW {n} {mark}" for n in needles if n != "ROW*"]
    force = wait_for(start, lambda l: FORCE_RE.search(l) is not None, 8)
    for w in want:
        wait_for(start, lambda l, w=w: w in l, 3)
    time.sleep(0.8)
    new = log_lines()[start:]
    shown = {w for w in want if any(w in l for l in new)}
    rows_new, rows_old = set(), set()
    if "ROW*" in needles:
        fi = next((k for k, l in enumerate(new) if "HR forceRecompose recomposers=" in l), len(new))
        for l in new[:fi + 1]:
            m = re.search(r"SHOW ROW(\d+) (\S+)", l)
            if m:
                (rows_new if m.group(2) == mark else rows_old).add(int(m.group(1)))
        rows_old -= rows_new
    st = last_state()
    pid = app_pid()
    crash = [l for l in new if CRASH_RE.search(l)]
    why = []
    if rc != 0:
        why.append(f"rc={rc}")
    if pid != pid0:
        why.append(f"pid {pid0}->{pid}")
    if crash:
        why.append(f"crash:{crash[0][:80]}")
    if shown != set(want):
        why.append(f"missing {sorted(set(want) - shown)}")
    if "ROW*" in needles and (len(rows_new) < 3 or rows_old):
        why.append(f"rows new={sorted(rows_new)} stale={sorted(rows_old)}")
    if force is None:
        why.append("no HR forceRecompose line")
    if st is None or st[1:] != ("3", "2", popup, dialog, backs) or st[0] != str(page):
        why.append(f"state={st}")
    screenshot(f"{label}.png")
    m = FORCE_RE.search(force or "")
    detail = (f"mark={mark} rc={rc} pid={pid} state=page={st[0] if st else '?'},plain={st[1] if st else '?'},"
              f"saved={st[2] if st else '?'},popup={st[3] if st else '?'},dialog={st[4] if st else '?'} "
              + (f"rows={min(rows_new)}-{max(rows_new)} " if rows_new else "")
              + (f"compositions={m.group(2)} invalidated={m.group(3)} recomposed={m.group(4)} "
                 f"frameMs={m.group(8)} totalMs={m.group(9)} " if m else "")
              + f"shot={label}.png" + (f" why: {'; '.join(why)}" if why else ""))
    record(label, not why, detail)
    return mark


def part1():
    pid0 = launch(True)
    record("launch-on", bool(pid0), f"pid={pid0}")
    tap("Plain+"); tap("Plain+"); tap("Plain+", r"plain=3 ")
    tap("Saved+"); tap("Saved+", r"saved=2 ")
    st = last_state()
    record("state-set", st is not None and st[1:3] == ("3", "2"), f"state={st}")

    check_step("home", pid0, 0, "false", ["HEADER", "HOME"])

    mark = f"s{mark_n}"
    tap("List", rf"SHOW ROW0 {mark}")
    # Scroll down (rows leave the screen; their compositions go to the reuse pool) and
    # part of the way back, so both reused and pooled item compositions exist.
    w, h = SCREEN
    for y0, y1 in ((0.75, 0.3), (0.75, 0.3), (0.4, 0.6)):
        subprocess.run(["axe", "swipe", "--start-x", str(w / 2), "--start-y", str(h * y0),
                        "--end-x", str(w / 2), "--end-y", str(h * y1), "--udid", UDID], capture_output=True)
        time.sleep(0.4)
    time.sleep(1.0)
    check_step("list", pid0, 1, "false", ["HEADER", "ROW*"])

    mark = f"s{mark_n}"
    tap("Popup", rf"SHOW POPUP {mark}")
    check_step("popup", pid0, 1, "true", ["POPUP", "HEADER", "ROW*"])
    tap("Close", r"popup=false")

    mark = f"s{mark_n}"
    tap("Dialog", rf"SHOW DIALOG {mark}")
    check_step("dialog", pid0, 1, "false", ["DIALOG", "HEADER", "ROW*"], dialog="true")
    tap("Done", r"dialog=false")

    mark = f"s{mark_n}"
    ok, why = True, ""
    try:
        tap("Home", rf"SHOW HOME {mark}")
        tap("List", rf"SHOW ROW0 {mark}")
        tap("Home", rf"SHOW HOME {mark}")
    except RuntimeError as e:
        ok, why = False, str(e)
    st = last_state()
    ok = ok and st is not None and st[:3] == ("0", "3", "2") and app_pid() == pid0
    screenshot("nav.png")
    record("nav", ok, f"Home/List/Home after 3 reloads show {mark}; state={st} shot=nav.png {why}")

    mark = f"s{mark_n}"
    tap("Back", rf"SHOW BACKPAGE {mark}")
    check_step("back", pid0, 3, "false", ["BACKPAGE", "HEADER"])
    mark = f"s{mark_n}"
    start = len(log_lines())
    subprocess.run(["axe", "gesture", "swipe-from-left-edge", "--udid", UDID], capture_output=True)
    swiped = wait_for(start, lambda l: f"BACK HANDLED {mark}" in l, 4)
    # Informational: synthetic edge swipes have not reached the app's screen-edge recognizer
    # on the simulator so far, so the check below sends the back event through the same
    # dispatcher instead (SimBack: DirectNavigationEventInput -> dispatcher -> BackHandler).
    print(f"back-gesture INFO left-edge swipe {'handled' if swiped else 'not recognised'}", flush=True)
    if not swiped:
        tap("SimBack", rf"BACK HANDLED {mark}")
    time.sleep(0.8)
    st = last_state()
    new = log_lines()[start:]
    ok = st is not None and st[0] == "3" and st[5] == "1" and app_pid() == pid0 \
        and any(f"BACK HANDLED {mark}" in l for l in new) and not any(CRASH_RE.search(l) for l in new)
    screenshot("back-handled.png")
    record("back-handler", ok, f"back event -> BACK HANDLED {mark} (reloaded handler); "
           f"{'edge swipe' if swiped else 'SimBack via DirectNavigationEventInput'}; state={st} shot=back-handled.png")

    mark = f"s{mark_n}"
    tap("Big", rf"SHOW BIG0 {mark}")
    check_step("big", pid0, 2, "false", ["HEADER", "BIG0", "BIG1", "BIG2"], backs="1")
    rows = {int(m.group(1)) for m in (re.search(r"SHOW BIG(\d+) " + re.escape(f"s{mark_n}"), l)
                                      for l in log_lines()) if m}
    record("big-size", len(rows) >= 15, f"{len(rows)} rows composed with the new mark "
           f"(~{len(rows) * 14 + 12} layout nodes on screen)")


def measure(force, page_label):
    rows = []
    pid0 = app_pid()
    tap(page_label, None)
    time.sleep(1.0)
    for i in range(1, RELOADS + 1):
        mark = set_mark()
        start = len(log_lines())
        rc, klib_ms, reload_ms, t_send = reload(f"t-{'on' if force else 'off'}-{page_label}-{i}")
        m = None
        if force:
            line = wait_for(start, lambda l: FORCE_RE.search(l) is not None, 6)
            m = FORCE_RE.search(line or "")
        else:
            time.sleep(1.5)
        new = log_lines()[start:]
        needle = "BIG0" if page_label == "Big" else "ROW0"
        fresh = any(f"SHOW {needle} {mark}" in l for l in new)
        row = {
            "mode": "on" if force else "off", "screen": page_label, "i": i, "rc": rc,
            "klib_ms": klib_ms, "reload_ms": reload_ms,
            "compositions": m.group(2) if m else "", "invalidated": m.group(3) if m else "",
            "recomposed": m.group(4) if m else "", "dispatch_ms": m.group(6) if m else "",
            "force_ms": m.group(7) if m else "", "frame_ms": m.group(8) if m else "",
            "reload_to_frame_ms": m.group(9) if m else "",
            "send_to_frame_ms": (int(m.group(10)) - t_send) if m else "",
            "screen_updated": int(fresh), "same_pid": int(app_pid() == pid0),
        }
        rows.append(row)
        print(f"  {row['mode']} {page_label} #{i} rc={rc} reload={reload_ms} "
              f"reload->frame={row['reload_to_frame_ms']} send->frame={row['send_to_frame_ms']} "
              f"invalidated={row['invalidated']} recomposed={row['recomposed']} updated={fresh}", flush=True)
        time.sleep(1.0)
    return rows


def stats(vals):
    v = [float(x) for x in vals if x not in ("", None, "timeout")]
    return (statistics.median(v), max(v), len(v)) if v else (None, None, 0)


def part2():
    rows = []
    for force in (True, False):
        launch(force)
        for page in ("List", "Big"):
            rows += measure(force, page)
        rss = subprocess.run(["ps", "-o", "rss=", "-p", app_pid()], capture_output=True, text=True).stdout.strip()
        rows.append({"mode": "on" if force else "off", "screen": "rss_kb_after", "i": 0, "rc": 0,
                     "klib_ms": "", "reload_ms": rss})
    keys = ["mode", "screen", "i", "rc", "klib_ms", "reload_ms", "compositions", "invalidated", "recomposed",
            "dispatch_ms", "force_ms", "frame_ms", "reload_to_frame_ms", "send_to_frame_ms",
            "screen_updated", "same_pid"]
    with open(os.path.join(OUT, "timing.csv"), "w", newline="") as f:
        wr = csv.DictWriter(f, fieldnames=keys, restval="")
        wr.writeheader()
        wr.writerows(rows)
    lines = ["mode screen   n  reload_ms(med/max)  reload->frame_ms(med/max)  send->frame_ms(med/max)  "
             "invalidated(med)  recomposed(med)  updated  rc!=0  pid_changed"]
    for mode in ("on", "off"):
        for page in ("List", "Big"):
            rs = [r for r in rows if r["mode"] == mode and r["screen"] == page]
            rl, rf, sf = stats([r["reload_ms"] for r in rs]), stats([r["reload_to_frame_ms"] for r in rs]), \
                stats([r["send_to_frame_ms"] for r in rs])
            inv, rec = stats([r["invalidated"] for r in rs]), stats([r["recomposed"] for r in rs])
            fmt = lambda s: "-" if s[0] is None else f"{s[0]:.0f}/{s[1]:.0f}"
            lines.append(f"{mode:4} {page:5} {len(rs):3}  {fmt(rl):18}  {fmt(rf):25}  {fmt(sf):23}  "
                         f"{'-' if inv[0] is None else int(inv[0]):16}  {'-' if rec[0] is None else int(rec[0]):15}  "
                         f"{sum(r['screen_updated'] for r in rs)}/{len(rs)}  "
                         f"{sum(1 for r in rs if r['rc'] != 0)}  {sum(1 for r in rs if not r['same_pid'])}")
    for mode in ("on", "off"):
        rss = [r["reload_ms"] for r in rows if r["mode"] == mode and r["screen"] == "rss_kb_after"]
        lines.append(f"app RSS after the {mode} run: {rss[0] if rss else '?'} KB")
    print("\n".join(lines), flush=True)
    return lines, rows


def main():
    global SCREEN
    skip_timing = "--skip-timing" in sys.argv[1:]
    if any(a not in ("--skip-timing",) for a in sys.argv[1:]):
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    shutil.copytree(os.path.join(HERE, "accept-stale"), SRC)
    SCREEN = b4.screen_points()
    rc, txt, ms = sh(["bash", "hotreload.sh", "package"], os.path.join(OUT, "package.out"))
    record("package", rc == 0, f"rc={rc} {ms} ms")
    if rc != 0:
        return 1
    try:
        part1()
        summary = []
        if not skip_timing:
            summary, _ = part2()
    except Exception as e:  # keep the partial results
        record("error", False, repr(e))
        summary = []
    passed = sum(1 for r in results if r[1])
    with open(os.path.join(OUT, "summary.txt"), "w") as f:
        for name, ok, detail in results:
            f.write(f"{name} {'PASS' if ok else 'FAIL'} {detail}\n")
        f.write("\n".join(summary) + "\n")
        f.write(f"summary: {passed}/{len(results)} passed\n")
    print(f"summary: {passed}/{len(results)} passed; details in {os.path.relpath(OUT, HERE)}/")
    print("note: the app now runs the stale test app; run 'hotreload.sh package' before other tests")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
