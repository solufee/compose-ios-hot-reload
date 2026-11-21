#!/usr/bin/env python3
"""#60: one warm restart -> relaunch steps (ms) as one line of key=value; --summary: medians per key."""
import re, sys, statistics as st
if sys.argv[1] == "--summary":
    rows = [dict(kv.split("=") for kv in l.split()[1:]) for l in open(sys.argv[2]) if l.strip()]
    keys = list(rows[0].keys())
    print(f"runs={len(rows)}")
    for k in keys:
        raw = [r[k] for r in rows if r.get(k, "na") != "na"]
        if raw and not all(re.fullmatch(r"-?\d+(\.\d+)?", x) for x in raw):   # a word per run (ready_by): count them
            print(f"{k:<26} " + " ".join(f"{w}={raw.count(w)}" for w in sorted(set(raw))))
            continue
        v = [float(x) for x in raw]
        print(f"{k:<26} median={st.median(v):8.0f} min={min(v):8.0f} max={max(v):8.0f}" if v else f"{k:<26} n/a")
    sys.exit()
base = sys.argv[1]
shim = [l.split(" ", 2) for l in open(base + "-shim.log")]
def call(kind, sub):
    s = next((float(t) for t, ev, rest in shim if ev == "START" and f"simctl {sub}" in rest), None)
    e = next((float(t) for t, ev, rest in shim if ev == "END" and f"simctl {sub}" in rest), None)
    return s, e
tl = [l.rstrip("\n").split("\t", 1) for l in open(base + "-timeline.txt")]
AFTER = [0.0]
def first(pat):
    # only lines of the new app process: after its simctl launch started
    return next((float(t) for t, line in tl if float(t) >= AFTER[0] and re.search(pat, line)), None)
rel = [l.rstrip("\n").split("\t", 1) for l in open(base + "-reload.out")]
warm_ok = next(((float(t), line) for t, line in rel if line.startswith("WARM OK")), (None, ""))
ts, te = call("t", "terminate"); us, ue = call("u", "uninstall"); is_, ie = call("i", "install"); ls, _ = call("l", "launch")
AFTER[0] = ls or 0.0
p = {
    "init": first(r"^HR InitModule"), "orc": first(r"^HR ORC create ok"), "port": first(r"^HR server: listening"),
    "boot0": first(r"^HR LoadBootstrap"), "reg": first(r"bootstrap payloads registered"),
    "loaded": first(r"bootstrap objects loaded"), "start": first(r"calling Konan_start"),
    "ehok": first(r"^EH OK"), "frame": first(r"^RENDER_APP"), "marker": first(r"^HR READY first-frame"),
}
def d(a, b):
    return "na" if a is None or b is None else f"{(b - a) * 1000:.0f}"
m = re.search(r"ms_relaunch=(\d+)", warm_ok[1])
rb = re.search(r"\bready=(\w[\w-]*)", warm_ok[1])
steps = {
    "terminate": d(ts, te),
    "pkg_to_uninstall": d(te, us),
    "uninstall": d(us, ue),
    "install": d(is_, ie),
    "install_to_launch": d(ie, ls),
    "launch_to_proc": d(ls, p["init"]),
    "orc_setup": d(p["init"], p["orc"]),
    "port_ready_after_launch": d(ls, p["port"]),
    "to_bootstrap": d(p["orc"], p["boot0"]),
    "bootstrap_register": d(p["boot0"], p["reg"]),
    "bootstrap_load": d(p["reg"], p["loaded"]),
    "to_konan_start": d(p["loaded"], p["start"]),
    "konan_start_to_frame": d(p["start"], p["frame"]),
    "frame_to_detected": d(p["frame"], warm_ok[0]),
    "frame_to_marker": d(p["frame"], p["marker"]),
    "marker_to_detected": d(p["marker"], warm_ok[0]),
    "kotlin_main_to_ready": d(p["start"], warm_ok[0]),
    "app_container": d(*call("c", "get_app_container")),
    "launch_to_frame": d(ls, p["frame"]),
    "uninstall_to_detected": d(us, warm_ok[0]),
    "ms_relaunch": m.group(1) if m else "na",
    # #62 item 3: how the plugin found the app ready (WARM OK ... ready=marker|state|user-marker|rule); na before 0.2.7
    "ready_by": rb.group(1) if rb else "na",
}
print(base.rsplit("/", 1)[-1] + " " + " ".join(f"{k}={v}" for k, v in steps.items()))
