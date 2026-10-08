#!/usr/bin/env python3
"""Negative offline hash/shape/semantic admission. No network or native verification."""
from pathlib import Path
import runpy,tempfile,shutil,json,hashlib
ROOT=Path(__file__).resolve().parent.parent
m=runpy.run_path(str(ROOT/'scripts/project-opcert-fixtures.py'))
count=0
def reject(name,call):
    global count
    try:call()
    except (ValueError,KeyError,IndexError,TypeError):
        count+=1;print('PASS:',name);return
    raise AssertionError('accepted '+name)
raw=(ROOT/'fixtures/opcert/evidence/preprod_70070331.cbor').read_bytes()
for name,b in [('empty',b''),('truncated',raw[:-1]),('trailing',raw+b'\0'),('indefinite',b'\x9f\xff'),('array limit',b'\x98\xff'),('length overflow',b'\x5b'+b'\xff'*8),('depth limit',b'\x81'*12+b'\x00'),('oversize',b'0'*65537)]:reject(name,lambda b=b:m['decode'](b))
with tempfile.TemporaryDirectory() as tmp:
    d=Path(tmp)/'opcert';shutil.copytree(ROOT/'fixtures/opcert',d)
    env=m['projections'].__globals__;env['D']=d;env['E']=d/'evidence'
    def mutate(name,fn,call=None):
        p=d/name;old=p.read_bytes();p.write_bytes(fn(old))
        try:reject(name,call or m['main'])
        finally:p.write_bytes(old)
    mutate('certificates.tsv',lambda b:b+b'\n')
    mutate('evidence/preprod_70070331.cbor',lambda b:b[:-1])
    mutate('evidence/OCert.hs',lambda b:b+b'\n')
    mutate('sha256.json',lambda b:json.dumps({}).encode())
    mutate('evidence/public-key-results.json',lambda b:b'x'*65537,lambda:m['projections']())
    def semantic(fn):
        def change(b):
            data=json.loads(b);fn(data);return json.dumps(data).encode()
        mutate('evidence/public-key-results.json',change,m['projections'])
    semantic(lambda d:d.reverse())
    semantic(lambda d:d[0].update(cold_key='00'*32))
    semantic(lambda d:d[0].update(counter=8))
    semantic(lambda d:d[0].update(opcert_message='00'*48))
    semantic(lambda d:d[0]['opcert_native_cases'].update(original=-1))
    semantic(lambda d:d[0]['opcert_native_cases'].update(**{'hot-bit':0}))
    semantic(lambda d:d[0]['opcert_native_cases'].pop('cold-key-bit'))
    def bad_control(b):
        data=json.loads(b);data[0]['message']='00'*48;return json.dumps(data).encode()
    mutate('evidence/serialization-controls.json',bad_control,m['projections'])
    (d/'extra').write_text('x');reject('extra unlisted file',m['main']);(d/'extra').unlink()
    p=d/'certificates.tsv';p.write_bytes(p.read_bytes()+b'\n')
    manifest=json.loads((d/'sha256.json').read_bytes());manifest['certificates.tsv']=hashlib.sha256(p.read_bytes()).hexdigest()
    (d/'sha256.json').write_text(json.dumps(manifest));reject('edited manifest cannot bypass embedded pin',m['main'])
print(f'{count} negative opcert fixture admission checks passed')
