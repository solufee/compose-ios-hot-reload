#!/usr/bin/env python3
"""#60 (b): APP MAIN / APP DID FINISH / first frame from watch-log.py timelines (ax<0|1>-<i>.tl). Usage: axparse.py <dir>"""
import sys,re,glob,statistics
rows={}
for f in sorted(glob.glob(sys.argv[1]+'/ax*.tl')):
    t={}
    for l in open(f, errors='replace'):
        p=l.rstrip('\n').split('\t',1)
        if len(p)<2: continue
        s=p[1].strip()
        for k,pat in (('ks','calling Konan_start'),('main','^APP MAIN'),('fin','^APP DID FINISH'),('frame','^RENDER_APP')):
            if k not in t and re.search(pat,s): t[k]=float(p[0])
    ms=lambda a,b: round((t[b]-t[a])*1000) if a in t and b in t else None
    r=(ms('ks','main'),ms('main','fin'),ms('fin','frame'),ms('ks','frame'))
    print(f.rsplit('/',1)[1], 'ks_to_main',r[0],'main_to_finish',r[1],'finish_to_frame',r[2],'ks_to_frame',r[3])
    rows.setdefault(f.rsplit('/',1)[1][:3],[]).append(r)
for k,v in rows.items():
    print('median', k, 'n=%d'%len(v), *[ (n, statistics.median([x[i] for x in v if x[i] is not None])) for i,n in enumerate(('ks_to_main','main_to_finish','finish_to_frame','ks_to_frame'))])
