#!/usr/bin/env python3
"""Direct JVM CLI regressions. First run ./scripts/sbtw app/runtimeClasspathFile compile."""
from pathlib import Path
import subprocess,tempfile,shutil,json,zipfile,os
r=Path(__file__).resolve().parent.parent; cp=(r/'app/target/runtime-classpath.txt').read_text();cmd=['java','-cp',cp,'lab.Main']; results=[]
def check(label,args,want):
 p=subprocess.run(cmd+args,cwd=r,capture_output=True,text=True,timeout=30)
 (r/f'docs/cli-vm-{label}.log').write_text(p.stdout+p.stderr+f'\nexit={p.returncode}; expected={want}\n')
 assert p.returncode==want,(label,p.returncode,p.stdout,p.stderr)
 results.append({'case':label,'exit':p.returncode,'expected':want})
check('valid',['vm'],0);check('help',['--help'],0);check('unknown-option',['--wat'],2);check('extra-args',['vm','a','b'],2);check('missing',['vm','/tmp/missing-vm-fixtures'],2)
with tempfile.TemporaryDirectory(prefix='vm-negative-') as temp:
 d=Path(temp)
 def reset():
  for p in d.iterdir(): p.unlink()
  for p in (r/'fixtures/plutus').glob('*.uplc*'): shutil.copy(p,d/p.name)
 reset();p=d/'addInteger-01.uplc';p.write_text(p.read_text()+' ');check('modified-source',['vm',str(d)],2)
 reset();p=d/'addInteger-01.uplc.budget.expected';p.write_text(p.read_text()+' ');check('modified-budget',['vm',str(d)],2)
 reset();p=d/'addInteger-01.uplc.expected';p.write_text(p.read_text()+' ');check('modified-result',['vm',str(d)],2)
 reset()
 for suffix in ['.uplc','.uplc.expected','.uplc.budget.expected']:
  a=d/('addInteger-01'+suffix);b=d/('addInteger-02'+suffix);old=a.read_bytes();a.write_bytes(b.read_bytes());b.write_bytes(old)
 check('swapped-triples',['vm',str(d)],2)
 reset();(d/'addInteger-01.uplc').write_text('x'*65537);check('oversized',['vm',str(d)],2)
 reset();(d/'divideInteger-zero.uplc').write_text('(program 1.0.0 (con bls12_381_G1_element 0x00))');check('bls-ingress',['vm',str(d)],2)
 (d/'malformed.tsv').write_text('bad');check('codec-malformed',[str(d/'malformed.tsv')],2)
 text=(r/'fixtures/cardano-golden.tsv').read_text();lines=text.splitlines();i=next(i for i,l in enumerate(lines) if l and not l.startswith('#'));fields=lines[i].split('\t');fields[-1]='00'*32;lines[i]='\t'.join(fields);(d/'mismatch.tsv').write_text('\n'.join(lines));check('codec-mismatch',[str(d/'mismatch.tsv')],1)
(r/'docs/cli-vm-verification.json').write_text(json.dumps(results,indent=2)+'\n');print(f'{len(results)} direct-Java CLI cases passed')
