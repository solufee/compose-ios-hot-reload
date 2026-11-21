#!/usr/bin/env python3
"""#60 (b): print the main-thread subtree under a frame matching ROOT from a `sample` report,
JIT addresses symbolized through the KALDO_PERF_MAP file. Usage: symtree.py sample.txt perf.map ROOT [min_pct] [max_depth]"""
import bisect, re, sys
sample, perf, root = sys.argv[1:4]
min_pct = float(sys.argv[4]) if len(sys.argv) > 4 else 2.0
max_depth = int(sys.argv[5]) if len(sys.argv) > 5 else 40
starts, ends, names = [], [], []
for l in open(perf, errors='replace'):
    p = l.rstrip('\n').split(' ', 2)
    if len(p) == 3:
        try: s, n = int(p[0], 16), int(p[1], 16)
        except ValueError: continue
        starts.append(s); ends.append(s + n); names.append(p[2])
order = sorted(range(len(starts)), key=starts.__getitem__)
starts = [starts[i] for i in order]; ends = [ends[i] for i in order]; names = [names[i] for i in order]
def sym(addr):
    i = bisect.bisect_right(starts, addr) - 1
    return names[i] if i >= 0 and addr < ends[i] else None
line_re = re.compile(r'^([ +!:|]*?)(\d+) (.*)$')
lines = open(sample, errors='replace').read().split('\n')
# main thread block
i0 = next(i for i, l in enumerate(lines) if 'Main Thread' in l)
nodes = []
for l in lines[i0 + 1:]:
    if not l.startswith('    +'): break
    m = line_re.match(l[4:])
    if not m: continue
    depth = len(m.group(1)) // 2
    txt = m.group(3); raw = txt
    if txt.startswith('???'):
        a = re.search(r'\[(0x[0-9a-f]+)', txt)
        s = sym(int(a.group(1), 16)) if a else None
        if s: txt = 'JIT ' + s
    txt = re.sub(r'\s+\[0x[0-9a-f,x]+\]$', '', txt)
    txt = re.sub(r'\(in [^)]*\) \+ [0-9,]+', lambda m: m.group(0).split(')')[0] + ')', txt)
    nodes.append((depth, int(m.group(2)), txt, raw))
r = next(k for k, n in enumerate(nodes) if re.search(root, n[3]) or re.search(root, n[2]))
d0, total = nodes[r][0], nodes[r][1]
print(f'root: {nodes[r][2][:160]}  samples={total}')
for k, (depth, cnt, txt, raw) in enumerate(nodes[r:]):
    if k and depth <= d0: break
    if depth > d0 and depth <= d0 + max_depth and cnt * 100.0 / total >= min_pct:
        print(f"{'  ' * (depth - d0)}{cnt:5d} {cnt*100.0/total:5.1f}%  {txt[:170]}")
