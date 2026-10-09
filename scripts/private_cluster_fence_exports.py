#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Map retained single-acquire native packets into existing bounded Scala inputs.

Caller must first verify helper pins, exit zero and owned-container cleanup.
This module binds bytes and attribution; it does not validate native ledger state.
"""
import decimal
import hashlib
import json
import os
from pathlib import Path
import stat

from private_cluster_sequence import PRE_PINS, ORACLE_PINS

LIMIT = 4 * 1024 * 1024
FILES = {'request.json', 'capture.json', 'original-debug-epoch.cbor',
    'original-whole-utxo.cbor', 'original-protocol.cbor', 'original-parameters.cbor',
    'derived-ledger.json', 'derived-utxo.json', 'derived-parameters.json', 'derived-protocol.json'}

def require(ok, why):
    if not ok: raise ValueError(why)

def sha(raw): return hashlib.sha256(raw).hexdigest()

def parse(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'duplicate JSON key')
            result[key] = value
        return result
    def bad(_): raise ValueError('nonfinite JSON')
    return json.loads(raw, object_pairs_hook=pairs, parse_float=decimal.Decimal, parse_constant=bad)

def read(path, limit=LIMIT):
    path = Path(path)
    require(path.parent.resolve() == path.parent.absolute(), 'symlinked source directory')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as stream:
        require(stat.S_ISREG(os.fstat(stream.fileno()).st_mode), 'regular source required')
        raw = stream.read(limit + 1)
    require(0 < len(raw) <= limit, 'source byte bound')
    return raw

def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n').encode()

def packet(directory, expected_point, expected_block_no):
    directory = Path(directory)
    require(type(expected_block_no) is int and 0 < expected_block_no < 2**64, 'concrete block number')
    require(type(expected_point) is dict and set(expected_point) == {'slot', 'hash'}
            and type(expected_point['slot']) is int and 0 <= expected_point['slot'] < 2**64
            and type(expected_point['hash']) is str and len(expected_point['hash']) == 64
            and all(c in '0123456789abcdef' for c in expected_point['hash']), 'concrete point')
    receipt_raw = read(directory / 'receipt.json', 16384)
    receipt = parse(receipt_raw)
    require(receipt.get('schema') == 1 and type(receipt.get('schema')) is int
            and receipt.get('kind') == 'single-acquire-supported-state-oracle', 'native packet receipt')
    require(receipt.get('point') == expected_point and receipt.get('blockNo') == expected_block_no
            and type(receipt.get('blockNo')) is int, 'receipt exact point/block')
    require(receipt.get('acquireCount') == 1 and type(receipt.get('acquireCount')) is int
            and receipt.get('reacquireCount') == 0 and type(receipt.get('reacquireCount')) is int
            and receipt.get('release') == 'sent-no-ack', 'single acquisition lifecycle')
    require(all(receipt.get(key) is False for key in ('runtimeImport', 'monetaryParity',
            'rewardSeedAdmission', 'fullLedgerValidation')), 'unsupported packet claims')
    require(set(receipt.get('fileSHA256', {})) == FILES, 'exact packet file set')
    originals = {name: read(directory / name, 72 * 1024 * 1024 if name == 'capture.json' else 8 * 1024 * 1024)
                 for name in FILES}
    require(all(sha(raw) == receipt['fileSHA256'][name] for name, raw in originals.items()), 'packet source digest')
    capture = parse(originals['capture.json']); request = parse(originals['request.json'])
    require(request.get('point') == expected_point and request.get('networkMagic') == 1082026,
            'isolated request exact point')
    require(capture.get('kind') == 'single-acquire-sustained-payloads'
            and all(capture.get(k) == expected_point for k in ('requestedPoint', 'acquiredPoint', 'finalPoint'))
            and all(type(capture.get(k)) is int and capture[k] == expected_block_no for k in ('blockNo', 'finalBlockNo'))
            and type(capture.get('acquireCount')) is int and capture['acquireCount'] == 1
            and type(capture.get('reacquireCount')) is int and capture['reacquireCount'] == 0
            and capture.get('release') == 'sent-no-ack', 'capture acquisition bracket')
    require(receipt.get('admissionChecks') == 'not-performed'
            and capture.get('queryEncoding') == 'GetCBOR-server-maxBound'
            and type(request.get('ntcVersion')) is int and 16 <= request['ntcVersion'] <= 23
            and type(capture.get('ntcVersion')) is int and capture['ntcVersion'] == request['ntcVersion'],
            'native encoding/version attribution')
    projection = capture.get('projection', {})
    require(projection.get('kind') == 'derived-native-supported-state'
            and projection.get('nativeSemanticRoundTrips') is True
            and projection.get('fullLedgerValidation') is False and projection.get('monetaryParity') is False,
            'native projection attribution')
    for key, name in (('epochHex', 'original-debug-epoch.cbor'), ('utxoHex', 'original-whole-utxo.cbor'),
                      ('protocolHex', 'original-protocol.cbor'), ('parametersHex', 'original-parameters.cbor')):
        require(capture.get(key) == originals[name].hex(), 'native original binding')
    for key, name in (('ledgerJsonHex', 'derived-ledger.json'), ('utxoJsonHex', 'derived-utxo.json'),
                      ('protocolJsonHex', 'derived-protocol.json'), ('parametersJsonHex', 'derived-parameters.json')):
        require(projection.get(key) == originals[name].hex() and isinstance(parse(originals[name]), dict),
                'native derived byte binding')
    ledger = parse(originals['derived-ledger.json']); protocol = parse(originals['derived-protocol.json'])
    require(type(ledger.get('lastEpoch')) is int and ledger['lastEpoch'] >= 0
            and type(protocol.get('lastSlot')) is int and protocol['lastSlot'] == expected_point['slot'],
            'derived point/epoch attribution')
    tip = dict(expected_point, block=expected_block_no, epoch=ledger['lastEpoch'], era='Conway')
    # These are the actual two LSQ bracket observations, represented for legacy
    # consumers. They are not CLI queries and are never called original CLI output.
    tips = encoded([dict(tip), dict(tip)])
    provenance = {'schema': 1, 'kind': 'native-packet-scala-input-mapping',
        'packetReceiptSHA256': sha(receipt_raw), 'packetFiles': receipt['fileSHA256'],
        'tipSource': 'same-acquisition-first-and-final-point-block-brackets',
        'representation': 'derived-native-json-and-hex-encoded-original-utxo',
        'originalCLIOutput': False, 'fullLedgerValidation': False,
        'monetaryParity': False, 'rewardSeedAdmission': False, 'runtimeImport': False}
    return originals, tips, provenance

def publish(destination, files, pins, profile, manifest_name, provenance):
    destination = Path(destination)
    require(destination.parent.resolve() == destination.parent.absolute(), 'real destination parent')
    require(set(pins.values()) <= set(files), 'complete manifest files')
    require(all(0 < len(files[name]) <= (20 * 1024 * 1024 if name == 'scala-sequence-capture.md' else LIMIT)
                for name in pins.values()), 'Scala input bound')
    hashes = {key: sha(files[name]) for key, name in pins.items()}
    manifest = ('format\t' + profile + '\n' + ''.join(k + '\t' + hashes[k] + '\n' for k in sorted(hashes))).encode()
    destination.mkdir(mode=0o700, exist_ok=False)
    # Manifest is installed last: partial failures remain evidence, not inputs.
    for name, raw in dict(files, **{'native-source-attribution.json': encoded(provenance)}).items():
        with (destination / name).open('xb') as stream: stream.write(raw)
    with (destination / manifest_name).open('xb') as stream: stream.write(manifest)
    return hashes

def prepare_context(packet_directory, destination, *, genesis_directory, seed_capture,
                    expected_point, expected_block_no):
    originals, tips, provenance = packet(packet_directory, expected_point, expected_block_no)
    genesis = read(Path(genesis_directory) / 'shelley-genesis.json')
    g = parse(genesis)
    require(g.get('networkId') == 'Testnet' and g.get('networkMagic') == 1082026
            and type(g.get('epochLength')) is int and g['epochLength'] == 1000,
            'bounded isolated fixture genesis')
    require(parse(originals['derived-ledger.json'])['lastEpoch'] == expected_point['slot'] // g['epochLength'],
            'native/genesis epoch mismatch')
    seed = read(seed_capture, 5 * LIMIT)
    rows = [parse(line) for line in seed.splitlines() if line.strip()]
    blocks = [row for row in rows if row.get('record') == 'transfer-range-block']
    require(len(blocks) == 2, 'exact two retained seed originals')
    provenance['seedCaptureSHA256'] = sha(seed)
    files = {'transfer-genesis.md': genesis, 'pre-tips.md': tips,
        'pre-ledger-state.md': originals['derived-ledger.json'],
        'pre-protocol-state.md': originals['derived-protocol.json'],
        'pre-parameters.md': originals['derived-parameters.json'],
        'pre-utxo.md': originals['derived-utxo.json'],
        'pre-utxo-cbor.md': (originals['original-whole-utxo.cbor'].hex() + '\n').encode(),
        'seed-capture.md': seed}
    hashes = publish(destination, files, PRE_PINS, 'coherent-sequence-context-v1',
                     'coherent-sequence-context.md', provenance)
    recipe = 'coherent-sequence-context-v1\n' + ''.join(k + '=' + hashes[k] + '\n' for k in sorted(hashes))
    return {'contextId': sha(recipe.encode()), 'manifest': str(Path(destination) / 'coherent-sequence-context.md')}

def prepare_oracle(packet_directory, destination, *, expected_point, expected_block_no,
                   capture_path, transaction_paths):
    originals, tips, provenance = packet(packet_directory, expected_point, expected_block_no)
    require(type(transaction_paths) is tuple and len(transaction_paths) == 2, 'exact transaction pair')
    transactions = [read(path, 131074) for path in transaction_paths]
    require(transactions[0] != transactions[1], 'distinct transactions')
    for raw in transactions:
        value = raw.decode('ascii').strip()
        require(0 < len(value) <= 131072 and len(value) % 2 == 0
                and all(c in '0123456789abcdef' for c in value), 'canonical transaction hex')
    files = {'post-tips.md': tips, 'post-ledger-state.md': originals['derived-ledger.json'],
        'post-protocol-state.md': originals['derived-protocol.json'],
        'post-parameters.md': originals['derived-parameters.json'],
        'post-utxo-cbor.md': (originals['original-whole-utxo.cbor'].hex() + '\n').encode(),
        'scala-sequence-capture.md': read(capture_path, 20 * 1024 * 1024),
        **{f'signed-transaction-{i}-cbor.md': raw for i, raw in enumerate(transactions)}}
    publish(destination, files, ORACLE_PINS, 'coherent-sequence-oracle-v1',
            'coherent-sequence-oracle.md', provenance)
    return Path(destination) / 'coherent-sequence-oracle.md'
