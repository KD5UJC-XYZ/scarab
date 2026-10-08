#!/usr/bin/env python3
"""Portable archived-corpus integrity/projection checks. No native code or external checkout."""
from pathlib import Path
import hashlib
import json
from collections import Counter
ROOT = Path(__file__).resolve().parent.parent
D = ROOT / 'fixtures/vrf'
def require(condition, message):
    if not condition:
        raise SystemExit(message)
pins = json.loads((D / 'sha256.json').read_text())
actual = {str(p.relative_to(D)) for p in D.rglob('*') if p.is_file()} - {'sha256.json'}
require(actual == set(pins), 'manifest file set mismatch')
for name, expected in pins.items():
    require(hashlib.sha256((D / name).read_bytes()).hexdigest() == expected, f'digest mismatch: {name}')
rows = json.loads((D / 'evidence/vectors.json').read_text())
require(len(rows) == 2048, 'original row count')
require(len({r['id'] for r in rows}) == 2048, 'duplicate original ids')
projection = ''.join(r['id'] + '\t' + '\t'.join('NULL' if r[k] is None else r[k] for k in ['pk', 'proof', 'alpha']) + '\n' for r in rows)
require(projection == (D / 'vectors.tsv').read_text(), 'original input projection mismatch')
expected = ''.join(r['id'] + '\t' + ('MALFORMED' if r['native03'] == 'EXCLUDED' else r['native03']) + '\n' for r in rows)
require(expected == (D / 'expected.tsv').read_text(), 'original oracle projection mismatch')
require(Counter(r['native03'].split(':')[0] for r in rows) == {'VALID': 3, 'INVALID': 2018, 'EXCLUDED': 27}, 'original status counts')
for base, count, output in [('helpers', 3426, 'helper-results.tsv'), ('additional', 2338, 'additional-results.tsv')]:
    records = json.loads((D / f'evidence/{base}.json').read_text())
    require(len(records) == count and len({r['id'] for r in records}) == count, f'{base} count or ids')
    require(''.join(r['id'] + '\t' + r['expected'] + '\n' for r in records) == (D / output).read_text(), f'{base} expected projection')
    if base == 'helpers':
        source = ''.join('\t'.join([r['id'], r['op']] + r['args']) + '\n' for r in records)
        require(Counter(r['op'] for r in records) == {'d': 612, 'u': 519, 'e': 240, 't': 2055}, 'helper category count')
    else:
        source = ''.join('\t'.join([r['id'], r['pk'], r['proof'], r['alpha']]) + '\n' for r in records)
        require(sum(r['id'].startswith('rerun-') for r in records) == 2021, 'repeated recheck count')
    require(source == (D / f'{base}.tsv').read_text(), f'{base} input projection')
affine = json.loads((D / 'evidence/affine-expected.json').read_text())
affine_rows = (D / 'affine-inputs.tsv').read_text().splitlines()
require(len(affine) == len(affine_rows) == 104, 'affine row count')
require(''.join(row.split('\t')[0] + '\t' + affine[row.split('\t')[0]] + '\n' for row in affine_rows) == (D / 'affine-results.tsv').read_text(), 'independent affine expected projection')
print(f'PASS: {len(pins)} pinned files; 2048 original, 2338 additional (2021 repeated), 3426 helper rows. Archived oracle integrity only, not fresh native execution.')
