#!/usr/bin/env python3
from private_corpus_gate import require_private_corpus
require_private_corpus()
"""Direct resolved-JVM read-only body-commitment CLI checks, with bounded subprocess lifetimes."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
PACKET=ROOT/'fixtures/body-commitment'
CP=(ROOT/'app/target/runtime-classpath.txt').read_text().strip()
CMD=['java','-cp',CP,'lab.Main','body-commitment']
rows=json.loads((PACKET/'expectations.json').read_text())['rows']
count=0

def check(name,args,code,cwd=ROOT,expected=None):
    global count
    out=subprocess.run(CMD+list(map(str,args)),cwd=cwd,capture_output=True,text=True,timeout=30)
    assert out.returncode==code,(name,out.returncode,out.stdout,out.stderr)
    if expected:
        actual=json.loads(out.stdout)
        for key,value in expected.items():assert actual[key]==value,(name,key,actual[key],value)
        for key in ('headerCryptography','consensus','ledger','sourceAuthentication'):
            assert actual[key]=='not_checked'
    count+=1;print(f'PASS: {name}, exit={code}')

for era in range(2,8):
    row=next(r for r in rows if r['era_tag']==era and r['body_commitment_matched'])
    check(row['era'],[PACKET/'blocks'/row['id']],0,expected={
        'rawSha256':row['raw_sha256'],'headerHash':row['header_hash'],
        'declaredSize':row['declared_size'],'actualSize':row['actual_size'],
        'declaredHash':row['declared_hash'],'actualHash':row['actual_hash'],
        'structurallyIndexed':True,'bodySizeMatched':True,'bodyHashMatched':True,'bodyCommitmentMatched':True})
toy=next(r for r in rows if not r['body_commitment_matched'])
check('toy size negative',[PACKET/'blocks'/toy['id']],1,expected={'bodySizeMatched':False,'bodyHashMatched':True,'bodyCommitmentMatched':False,'declaredSize':2345,'actualSize':6950})
check('help',['--help'],0);check('missing argument',[],2);check('unknown flag',['--wat'],2);check('extra arguments',['a','b'],2)
with tempfile.TemporaryDirectory(prefix='body-commitment-cli-') as temp:
    work=Path(temp)
    edge_rows=json.loads((PACKET/'edges.json').read_text())['rows']
    for eid,wanted in [('era-6-empty',0),('era-6-hash-only',1),('era-6-size-zero',1),('era-6-both',1),('era-6-size-word32-overflow',2),('era-6-invalid-out-of-range',0),('trailing',2),('empty',2)]:
        edge=next(r for r in edge_rows if r['id']==eid)
        path=work/(eid+'.cbor');raw=bytes.fromhex(edge['raw_hex']);path.write_bytes(raw)
        check('portable '+eid,[path.name],wanted,cwd=work)
        assert path.read_bytes()==raw, 'CLI changed input'
    check('missing file',[work/'missing.cbor'],2)
    check('directory',[work],2)
    oversized=work/'oversized.cbor';oversized.write_bytes(bytes(1048577));check('oversized',[oversized],2)
    target=work/'era-6-empty.cbor';link=work/'link';link.symlink_to(target);check('symlink',[link],2)
    if hasattr(os,'mkfifo'):
        fifo=work/'fifo';os.mkfifo(fifo);check('FIFO rejected before open',[fifo],2)
    original={str(p.relative_to(work)):hashlib.sha256(p.read_bytes()).hexdigest() for p in work.iterdir() if p.is_file() and not p.is_symlink()}
    check('repeat read-only',[target],0)
    assert original=={str(p.relative_to(work)):hashlib.sha256(p.read_bytes()).hexdigest() for p in work.iterdir() if p.is_file() and not p.is_symlink()}
print(f'{count} direct-JVM body-commitment CLI checks passed')
