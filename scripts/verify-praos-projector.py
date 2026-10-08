#!/usr/bin/env python3
"""Negative offline fixed-fixture admission checks; no network or native invocation."""
from pathlib import Path
import runpy,tempfile,shutil,json,hashlib
ROOT=Path(__file__).resolve().parent.parent
m=runpy.run_path(str(ROOT/'scripts/project-praos-fixtures.py'))
def reject(name,call):
    try:call()
    except (ValueError,KeyError,IndexError,TypeError): print('PASS:',name);return
    raise AssertionError('accepted '+name)
raw=(ROOT/'fixtures/praos/evidence/preprod_70070331.cbor').read_bytes()
for name,b in [('truncated',raw[:-1]),('trailing',raw+b'\0'),('indefinite',b'\x9f\xff'),('array limit',b'\x98\xff'),('length overflow',b'\x5b'+b'\xff'*8),('depth limit',b'\x81'*12+b'\x00')]:reject(name,lambda b=b:m['decode'](b))
with tempfile.TemporaryDirectory() as tmp:
    d=Path(tmp)/'praos';shutil.copytree(ROOT/'fixtures/praos',d)
    env=m['projections'].__globals__;env['D']=d;env['E']=d/'evidence'
    def mutate(name,fn):
        p=d/name;old=p.read_bytes();p.write_bytes(fn(old))
        try:reject(name,m['main'])
        finally:p.write_bytes(old)
    mutate('certificates.tsv',lambda b:b+b'\n')
    mutate('evidence/preprod_70070331.cbor',lambda b:b[:-1])
    mutate('evidence/amaru-store.rs',lambda b:b.replace(b'epoch: Epoch::from(165)',b'epoch: Epoch::from(166)'))
    def evidence_case(name,fn):
        p=d/'evidence'/name;old=p.read_bytes();data=json.loads(old);fn(data);p.write_text(json.dumps(data))
        try:reject('semantic '+name,m['projections'])
        finally:p.write_bytes(old)
    evidence_case('epoch166-header-results.json',lambda d:d[0].update(alpha='00'*32))
    evidence_case('epoch166-header-results.json',lambda d:d[0].update(nonce='00'*32))
    evidence_case('mutation-results.json',lambda d:d.reverse())
    evidence_case('alpha-controls.json',lambda d:d[0].update(alpha='00'*32))
    p=d/'evidence/amaru-store.rs';old=p.read_bytes();p.write_bytes(old.replace(b'epoch: Epoch::from(165)',b'epoch: Epoch::from(166)'))
    reject('source epoch semantic binding',m['projections']);p.write_bytes(old)
    (d/'unlisted').write_text('x');reject('extra unlisted file',m['main'])
print('15 negative Praos fixture admission checks passed')
