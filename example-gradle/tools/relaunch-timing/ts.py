#!/usr/bin/env python3
import sys, time
for line in sys.stdin:
    sys.stdout.write(f"{time.time():.3f}\t{line}"); sys.stdout.flush()
