#!/usr/bin/env python3
"""Stamp every new line of a log file with the host time it appears (#60). Handles truncation.
   watch-log.py <log> <out> <stop-file>"""
import os, sys, time
log, out, stop = sys.argv[1:]
pos = os.path.getsize(log) if os.path.exists(log) else 0
buf = b""
with open(out, "w") as o:
    while not os.path.exists(stop):
        try:
            size = os.path.getsize(log)
        except OSError:
            size = 0
        if size < pos:
            pos, buf = 0, b""
        if size > pos:
            with open(log, "rb") as f:
                f.seek(pos); data = f.read(size - pos)
            pos = size; buf += data
            *lines, buf = buf.split(b"\n")
            t = time.time()
            for l in lines:
                o.write(f"{t:.3f}\t{l.decode('utf-8', 'replace').rstrip(chr(13))}\n")
            o.flush()
        time.sleep(0.01)
