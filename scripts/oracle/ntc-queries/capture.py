#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only native LSQ capture: acquired point, original requests/replies and unwrapped values."""
import argparse
import hashlib
import json
import pathlib
import sys

p = argparse.ArgumentParser()
p.add_argument('--container', required=True)
p.add_argument('--socket', default='/work/env/socket/node1/sock')
p.add_argument('--magic', type=int, default=1082026)
p.add_argument('--out', type=pathlib.Path, required=True)
p.add_argument('--versions', default='23,22,19')
a = p.parse_args()
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3]))
from scripts.devnet import cborspan, ntcquery
from scripts.devnet.cbor import Tag, enc

a.out.mkdir(parents=True, exist_ok=True)
manifest = {'container': a.container, 'socket': a.socket, 'networkMagic': a.magic, 'versions': {}}
def hfc(q):
    return [0, [0, [6, q]]]
def exact_result(raw):
    outer = cborspan.children(raw, 0)
    if len(outer) != 2 or raw[outer[0][0]:outer[0][1]] != b'\x04':
        raise ValueError('not MsgResult')
    wrapped = cborspan.children(raw, outer[1][0])
    if len(wrapped) != 1:
        raise ValueError('not QueryIfCurrent success')
    start, end = wrapped[0]
    return raw[start:end]
def record(session, label, query, directory):
    request = enc(hfc(query))
    value = session.query_raw(hfc(query))
    raw = session.last_raw
    result = exact_result(raw)
    (directory / f'{label}.request.cbor').write_bytes(request)
    (directory / f'{label}.reply.cbor').write_bytes(raw)
    (directory / f'{label}.result.cbor').write_bytes(result)
    return {'label': label, 'requestHex': request.hex(), 'replySha256': hashlib.sha256(raw).hexdigest(),
            'resultSha256': hashlib.sha256(result).hexdigest(), 'resultBytes': len(result)}

with ntcquery.LocalStateQuery(a.container, a.socket, a.magic, version=23) as initial:
    initial.acquire()
    point = initial.query_raw([3])
    deposits = initial.query_raw(hfc([22, Tag(258, [])]))[0]
    credentials = sorted(deposits, key=repr)[:3]
manifest['point'] = {'slot': point[0], 'hash': point[1].hex()}
coins = [[0, 0], [0, 1], [0, 1000000000]]
members = sorted(coins + [[1, list(c)] for c in credentials], key=enc)
for version in map(int, a.versions.split(',')):
    directory = a.out / f'v{version}'
    directory.mkdir(exist_ok=True)
    queries = [('DebugNewEpochState', [12]), ('RewardInfoPools', [18]),
               ('RewardInfoPools-CBOR', [9, [18]]),
               ('NonMyopic-empty', [2, Tag(258, [])]),
               ('NonMyopic-members', [2, Tag(258, members)]),
               ('NonMyopic-members-CBOR', [9, [2, Tag(258, members)]])]
    if version >= 23:
        queries += [('LedgerPeerSnapshot-all', [34, 0]), ('LedgerPeerSnapshot-big', [34, 1]),
                    ('LedgerPeerSnapshot-all-CBOR', [9, [34, 0]]),
                    ('LedgerPeerSnapshot-big-CBOR', [9, [34, 1]])]
    else:
        queries += [('LedgerPeerSnapshot-legacy', [34]), ('LedgerPeerSnapshot-legacy-CBOR', [9, [34]])]
    with ntcquery.LocalStateQuery(a.container, a.socket, a.magic, version=version) as session:
        session.acquire(point)
        manifest['versions'][str(version)] = [record(session, label, query, directory) for label, query in queries]
(a.out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({'point': manifest['point'], 'versions': {v: len(qs) for v, qs in manifest['versions'].items()}, 'out': str(a.out)}))
