#!/usr/bin/env python3
from private_corpus_gate import require_private_corpus
require_private_corpus()
"""Offline source/corpus/table/copy audit, separate from Scala and reference-runtime evidence."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PACKET = ROOT / 'fixtures/body-commitment'
PIN = '9dd525dd67dbc636e1322e570088c0260205c48c5a75372821807824c0336328'
assert hashlib.sha256((PACKET/'SHA256SUMS').read_bytes()).hexdigest() == PIN, 'manifest pin'
expected = {}
for line in (PACKET/'SHA256SUMS').read_text().splitlines():
    digest, name = line.split('  ')
    assert name not in expected and not Path(name).is_absolute() and '..' not in Path(name).parts
    expected[name] = digest
actual = {str(p.relative_to(PACKET)) for p in PACKET.rglob('*') if p.is_file() and '__pycache__' not in p.parts}
assert actual == set(expected) | {'SHA256SUMS'}, 'unexpected/missing fixture file'
for name, digest in expected.items():
    assert hashlib.sha256((PACKET/name).read_bytes()).hexdigest() == digest, name
resources = ROOT/'core/src/test/resources/body-commitment'
for source in list((PACKET/'blocks').iterdir()) + [PACKET/'expectations.tsv',PACKET/'edges.tsv']:
    assert (resources/source.relative_to(PACKET)).read_bytes() == source.read_bytes(), source.name
for entry in json.loads((PACKET/'source/provenance.json').read_text()):
    era = entry['url'].split('/eras/')[1].split('/')[0]
    filename = era + ('-Internal.hs' if era in ('shelley','alonzo') else '-BlockBody.hs')
    raw = (PACKET/'source'/filename).read_bytes()
    assert len(raw)==entry['size']
    assert hashlib.sha256(raw).hexdigest()==entry['sha256']
    assert hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==entry['expectedGitBlob']==entry['gitBlob']
rows=json.loads((PACKET/'expectations.json').read_text())['rows']
manifest=json.loads((PACKET/'source/new-segment-provenance.json').read_text())
for archive in manifest['archives']:
    assert archive['success'] is True
    for i,sample in enumerate(archive['blocks']):
        row=next(r for r in rows if r['id']==f"mainnet-{archive['era']}-{i}.cbor")
        assert row['raw_sha256']==sample['sha256'] and row['header_hash']==sample['header_hash']
        assert row['era_tag']==sample['era_tag'] and row['raw_bytes']==sample['block_bytes']
subprocess.run([sys.executable,'-B',str(PACKET/'project.py'),'--verify'],check=True,timeout=30)
subprocess.run([sys.executable,'-B',str(PACKET/'edge_cases.py')],check=True,timeout=30)
print('36 original body observations, 347 synthetic cases, five resource checks, source blobs, pinned tables and exact resource copies passed')
