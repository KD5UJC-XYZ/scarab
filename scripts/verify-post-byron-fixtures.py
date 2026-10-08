#!/usr/bin/env python3
from private_corpus_gate import require_private_corpus
require_private_corpus()
"""Portable offline checks of previously retained independent indexing expectations."""
from pathlib import Path
import hashlib
import importlib.util
import json

ROOT = Path(__file__).resolve().parents[1]
P = ROOT / 'fixtures/post-byron'
spec = importlib.util.spec_from_file_location('independent_spans', P / 'independent_spans.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
for line in (P / 'SHA256SUMS').read_text().splitlines():
    digest, name = line.split('  ')
    assert hashlib.sha256((P / name).read_bytes()).hexdigest() == digest, name
rows = json.loads((P / 'expectations.json').read_text())['rows']
mainnet = {x['hash']: x for x in json.loads((P / 'mainnet-lookup.json').read_text())}
projection = []
chain = []
for r in rows:
    name = Path(r['file']).name
    if r['network_label'] == 'mainnet-source-provenanced':
        assert r['header_hash'] in mainnet
        assert mainnet[r['header_hash']]['abs_slot'] == r['slot']
    if name in ('alonzo1.block', 'babbage1.block', 'conway1.block'):
        assert r['network_label'] == 'network-unestablished'
    raw = (P / name).read_bytes()
    assert len(raw) == r['raw_bytes']
    assert hashlib.sha256(raw).hexdigest() == r['raw_sha256']
    length, header_hash, parent = m.block(raw)
    assert (length, header_hash, parent) == (len(raw), r['header_hash'], r['parent_hash'])
    root = m.item(raw); env = root[4]; payload = env[1][4]; header = payload[0]; body = header[4][0][4]
    assert m.decode_scalar(raw, env[0]) == r['era_tag']
    assert len(payload) == r['block_arity'] and len(header[4]) == r['header_arity']
    assert len(body) == r['header_body_arity']
    assert (header[0], header[1]-header[0]) == (r['header_offset'], r['header_length'])
    assert m.decode_scalar(raw, body[0]) == r['encoded_block_no_or_chain_difficulty']
    assert m.decode_scalar(raw, body[1]) == r['slot']
    version = body[9][4] if r['era_tag'] >= 6 else body[13:15]
    assert [m.decode_scalar(raw, x) for x in version] == r['protocol_version']
    assert (ROOT / 'core/src/test/resources/post-byron' / name).read_bytes() == raw
    projection.append('\t'.join(str(x) for x in [name, r['era_tag'], r['encoded_block_no_or_chain_difficulty'], r['slot'], r['header_hash'], r['parent_hash'], r['raw_sha256'], r['header_offset'], r['header_length']]))
    if name.startswith('02019-'):
        chain.append(r)
expected = '\n'.join(projection) + '\n'
assert (P / 'expectations.tsv').read_text() == expected
assert (ROOT / 'core/src/test/resources/post-byron/expectations.tsv').read_text() == expected
assert b''.join((P / Path(r['file']).name).read_bytes() for r in chain) == (P / '02019.chunk').read_bytes()
for entry in json.loads((P / 'upstream-tree-entries.json').read_text())['entries']:
    name = Path(entry['path']).name
    raw = (P / name).read_bytes()
    original = raw.hex().encode() if name.endswith('.block') else raw
    assert len(original) == entry['size']
    assert hashlib.sha1(b'blob ' + str(len(original)).encode() + b'\0' + original).hexdigest() == entry['sha']
lookup = {x['hash']: x for x in json.loads((P / 'preprod-lookup.json').read_text())}
for i, row in enumerate(chain):
    assert row['network_label'] == 'preprod-source-provenanced'
    assert lookup[row['header_hash']]['abs_slot'] == row['slot']
    assert lookup[row['header_hash']]['parent_hash'] == row['parent_hash']
    if i:
        assert row['parent_hash'] == chain[i-1]['header_hash']
        assert row['slot'] > chain[i-1]['slot']
        assert (ROOT / f'fixtures/chain-fetch/babbage/babbage-{i-1}.cbor').read_bytes() == (P / Path(row['file']).name).read_bytes()
manifest = (ROOT / 'fixtures/chain-fetch/babbage/source.tsv').read_bytes()
config = dict(x.split('\t') for x in (ROOT / 'fixtures/chain-fetch/babbage.tsv').read_text().splitlines())
assert config['manifestSha256'] == hashlib.sha256(manifest).hexdigest()
assert config['after'] == f"{chain[0]['slot']}:{chain[0]['header_hash']}"
assert manifest.decode().splitlines()[1] == 'preprod-source-provenanced\t' + config['after']
print(f'{len(rows)} retained post-Byron fixtures: independent spans, hashes, points, copies and preprod selection passed')
