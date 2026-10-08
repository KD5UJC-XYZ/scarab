#!/usr/bin/env python3
"""Negative offline Sum6 fixture admission; no native oracle or network."""
from pathlib import Path
import runpy,tempfile,shutil,json,hashlib
ROOT=Path(__file__).resolve().parent.parent
m=runpy.run_path(str(ROOT/'scripts/project-sum6-fixtures.py'))
count=0
def reject(name,call):
    global count
    try:call()
    except (ValueError,KeyError,IndexError,TypeError):
        count+=1;print('PASS:',name);return
    raise AssertionError('accepted '+name)
raw=(ROOT/'fixtures/sum6/evidence/preprod_70070331.cbor').read_bytes()
for name,b in [('empty',b''),('truncated',raw[:-1]),('trailing',raw+b'\0'),('indefinite',b'\x9f\xff'),('array limit',b'\x98\xff'),('length overflow',b'\x5b'+b'\xff'*8),('depth limit',b'\x81'*12+b'\x00')]:reject(name,lambda b=b:m['decode'](b))
with tempfile.TemporaryDirectory() as tmp:
    d=Path(tmp)/'sum6';shutil.copytree(ROOT/'fixtures/sum6',d)
    env=m['projections'].__globals__;env['D']=d;env['E']=d/'evidence'
    m['main']()
    def mutate(name,fn,call=None):
        p=d/name;old=p.read_bytes();p.write_bytes(fn(old))
        try:reject(name,call or m['main'])
        finally:p.write_bytes(old)
    for name in ['originals.tsv','observations.tsv','evidence/preprod_70070331.cbor','evidence/KES-Sum.hs','evidence/historical-oracle.json']:
        mutate(name,lambda b:b+b'\n')
    mutate('sha256.json',lambda b:b'{}')
    mutate('observations.tsv',lambda b:b'x'*(1024*1024+1))
    mutate('evidence/sources.json',lambda b:b'x'*(1024*1024+1),m['projections'])
    def semantic(fn):
        def change(b):
            data=json.loads(b);fn(data);return json.dumps(data).encode()
        mutate('evidence/sources.json',change,m['projections'])
    semantic(lambda d:d.reverse())
    semantic(lambda d:d[0].update(relative_period=34))
    semantic(lambda d:d[0].update(start_period=504))
    semantic(lambda d:d[0].update(sha256='00'*32))
    (d/'extra').write_text('x');reject('extra unlisted file',m['main']);(d/'extra').unlink()
    p=d/'originals.tsv';p.write_bytes(p.read_bytes()+b'\n')
    manifest=json.loads((d/'sha256.json').read_bytes());manifest['originals.tsv']=hashlib.sha256(p.read_bytes()).hexdigest()
    (d/'sha256.json').write_text(json.dumps(manifest));reject('edited manifest cannot bypass embedded pin',m['main'])
print(f'{count} negative Sum6 fixture admission checks passed')
