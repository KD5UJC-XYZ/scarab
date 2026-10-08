#!/usr/bin/env python3
from private_corpus_gate import require_private_corpus
require_private_corpus()
"""Offline fixed packet/source/copy/projection audit. Never executes native crypto or builds."""
from pathlib import Path
import hashlib,json,subprocess,sys
if not __debug__: raise SystemExit('optimized Python is unsupported for verification')
if len(sys.argv)!=1: raise SystemExit('usage: verify-block-evidence-projector.py')
R=Path(__file__).resolve().parents[1];P=R/'fixtures/block-evidence'
PIN='4aaadd430e1a0cdd375d20f0c181de6a8300a15a5a51e02a6eeeaff088805b25'
assert hashlib.sha256((P/'SHA256SUMS').read_bytes()).hexdigest()==PIN,'manifest pin'
expected={}
for line in (P/'SHA256SUMS').read_text().splitlines():
 digest,name=line.split('  ')
 assert name not in expected and not Path(name).is_absolute() and '..' not in Path(name).parts
 expected[name]=digest
actual={str(p.relative_to(P)) for p in P.rglob('*') if p.is_file() and '__pycache__' not in p.parts}
assert actual==set(expected)|{'SHA256SUMS'}
for name,digest in expected.items():assert hashlib.sha256((P/name).read_bytes()).hexdigest()==digest,name
resources=R/'core/src/test/resources/block-evidence'
for p in list((P/'blocks').iterdir())+list((P/'contexts').iterdir())+[P/'expectations.tsv',P/'edges.tsv',P/'profile-sha256.txt']:
 assert (resources/p.relative_to(P)).read_bytes()==p.read_bytes(),str(p)
closure=P/'source/encoding-closure'
for f in json.loads((closure/'retained-files.json').read_text())['files']:
 assert hashlib.sha256((closure/f['path']).read_bytes()).hexdigest()==f['sha256'],f['path']
# Recheck actual same-input provenance links rather than only the outer checksum manifest.
observations=json.loads((P/'oracle/observations.json').read_text())['observations']
source_pins=json.loads((P/'source/context-source-pins.json').read_text())['files']
genesis_raw=(P/'source/mainnet-shelley-genesis.json').read_bytes()
genesis=json.loads(genesis_raw)
assert (genesis['slotsPerKESPeriod'],genesis['maxKESEvolutions'])==(129600,62)
gp=source_pins['cardano-node/configuration/cardano/mainnet-shelley-genesis.json']
assert hashlib.sha256(genesis_raw).hexdigest()==gp['sha256']
assert hashlib.sha1(b'blob '+str(len(genesis_raw)).encode()+b'\0'+genesis_raw).hexdigest()==gp['git_blob']
segments=json.loads((P/'source/full-block-manifest.json').read_text())
by_path={'historical-chain-data/'+b['file']:b for a in segments['archives'] for b in a['blocks']}
preprod={b['hash']:b for b in json.loads((P/'source/preprod-explorer.json').read_text())}
for o in observations:
 assert hashlib.sha256((P/'blocks'/o['id']).read_bytes()).hexdigest()==o['raw_block_sha256']
 config=P/'source'/('amaru-global-parameters.rs' if 'preprod-' in str(o['context']['network']) else 'mainnet-shelley-genesis.json')
 assert hashlib.sha256(config.read_bytes()).hexdigest()==o['context']['period_config_source_sha256']
 if o['path'] in by_path:
  b=by_path[o['path']]
  assert (b['sha256'],b['header_hash'],b['slot'],b['height'])==(o['raw_block_sha256'],o['header_hash'],o['slot'],o['block_number'])
 elif o['header_hash'] in preprod:
  b=preprod[o['header_hash']]
  assert (b['abs_slot'],b['block_height'],b['parent_hash'])==(o['slot'],o['block_number'],o['parent_hash'])
 else:assert o['context']['network'] is None and 'conditional-' in o['context']['applicability']
subprocess.run([sys.executable,'-B',str(P/'project.py'),'--verify'],check=True,timeout=30)
for args in [('-O','-B',str(P/'project.py'),'--verify'),('-B',str(P/'project.py'),'--typo')]:
 result=subprocess.run([sys.executable,*args],capture_output=True,text=True,timeout=10)
 assert result.returncode!=0,'verification guard did not reject'
for name,digest in expected.items():assert hashlib.sha256((P/name).read_bytes()).hexdigest()==digest,'guard rewrote '+name
print('15 original projections, 24 synthetic cases, source closure, exact resource copies and optimized/argument guards passed')
