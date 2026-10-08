#!/usr/bin/env python3
"""Offline, fail-closed, version-pinned Haskell/Blueprint/Amaru fixture projector.
Not a general ledger decoder. No external dependencies; no floating-point quantities.
Run from any directory; --check verifies existing derived artifacts without writing.
"""
import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'fixtures/ledger'
PPHASH = '23a62c9e17bfb1c9755134f173e29e7ff87f9991a84b5bb40ec177b262647633'
ARCHIVE_HASH = '33de88ffd3fe1f82326704056264bfcbd62231079e1f88b67fc60eb74da10e40'
SOURCES = {
    'shelley': ('e29fa93400521ce6b15b357a25ed61afdc81e8fd18cd15ebbc29f16e30019123', 'ShelleyImpSpec'),
    'mary': ('e4229e5189a03c0489b3f64ece510812a61cd3a3853ee50e19f5587965385ab2', 'MaryImpSpec'),
}


PINNED_FILES = {'fixtures/ledger/upstream/amaru-blueprint-README.md': '22d2e95edafbb3612e49813d2cdcafa8c1afd28d176ce3fd4eea71dd4719f4fb',
 'fixtures/ledger/upstream/amaru-evaluate_ledger_states.rs': '560c38193e035e20b6ab87529842b90bf9a025fbcc9de68a44129365ddac5bbb',
 'fixtures/ledger/upstream/amaru-fork-MaryUtxoSpec.hs': '3d4e36b9b759a7bc75593290f71e657b71dc7fa58cdac119da59f4e51924aed6',
 'fixtures/ledger/upstream/amaru-fork-ShelleyImpTest.hs': '34c4138d24c9742a9963520f45e6c88b803c1743956b799832a8f358de8b4a42',
 'fixtures/ledger/upstream/amaru-fork-ShelleyUtxoSpec.hs': '25724216d08356d839849bc9f4a58f2435dc9060181850a494876bcf15cd874a',
 'fixtures/ledger/upstream/vectors.tar.gz': '33de88ffd3fe1f82326704056264bfcbd62231079e1f88b67fc60eb74da10e40',
 'fixtures/licenses/amaru-LICENSE': '59899c6091b540582ed617e8eeaac4919dc985ccfc35459ee9752b699be5205b',
 'fixtures/licenses/blueprint-conformance-LICENSE': '5bdd73433593173b55328fdc4cc01e826597e73cf52f93388a0ee786d39067cf',
 'fixtures/licenses/cardano-ledger-conformance-LICENSE': '0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594',
 'fixtures/licenses/cardano-ledger-conformance-NOTICE': '58721f8b6ca67f0fcbe1cd739b384fec3126a35f7d79951aedaa2bc3863a3162'}

def require(ok, message):
    if not ok:
        raise ValueError(message)


@dataclass(frozen=True)
class Node:
    major: int
    value: object
    start: int
    end: int
    payload_start: int

    def identity(self):
        if self.major in (4, 5):
            v = tuple(n.identity() for n in self.value)
            if self.major == 5:
                v = frozenset(zip(v[::2], v[1::2]))
        elif self.major == 6:
            v = (self.value[0], self.value[1].identity())
        else:
            v = self.value
        return self.major, v


def decode(raw):
    """Bounded CBOR with semantic duplicate-map rejection, preserving source ranges.

    Indefinite arrays/maps are supported; all other indefinite forms, floating point,
    undefined/simple extensions, unknown tags, truncated items and trailing bytes fail.
    The additional-info discriminator is retained: a definite length of 31 is valid.
    """
    require(len(raw) <= 1_000_000, 'CBOR byte limit')
    count = 0

    def read(pos, depth=0):
        nonlocal count
        count += 1
        require(depth <= 64 and count <= 100_000, 'CBOR resource limit')
        require(pos < len(raw), 'truncated CBOR header')
        start = pos
        head = raw[pos]
        pos += 1
        major, ai = head >> 5, head & 31
        indefinite = ai == 31
        require(ai not in (28, 29, 30), 'reserved CBOR additional info')
        number = ai
        if ai in (24, 25, 26, 27):
            width = 1 << (ai - 24)
            require(pos + width <= len(raw), 'truncated CBOR argument')
            number = int.from_bytes(raw[pos:pos + width], 'big')
            pos += width
        payload_start = pos
        require(not indefinite or major in (4, 5), 'unsupported indefinite CBOR')
        if major in (0, 1):
            value = number if major == 0 else -1 - number
        elif major in (2, 3):
            require(pos + number <= len(raw), 'truncated CBOR string')
            value = raw[pos:pos + number]
            if major == 3:
                value.decode('utf-8', errors='strict')
            pos += number
        elif major in (4, 5):
            value = []
            if indefinite:
                while True:
                    require(pos < len(raw), 'missing CBOR break')
                    if raw[pos] == 255:
                        pos += 1
                        break
                    child, pos = read(pos, depth + 1)
                    value.append(child)
            else:
                n = number * (2 if major == 5 else 1)
                require(n <= 100_000, 'CBOR container limit')
                for _ in range(n):
                    child, pos = read(pos, depth + 1)
                    value.append(child)
            if major == 5:
                require(len(value) % 2 == 0, 'odd CBOR map')
                keys = [n.identity() for n in value[::2]]
                require(len(keys) == len(set(keys)), 'duplicate CBOR map key')
            value = tuple(value)
        elif major == 6:
            require(number in (30, 258), 'unknown CBOR tag')
            child, pos = read(pos, depth + 1)
            require(child.major == 4, 'tag requires array')
            if number == 30:
                require(len(child.value) == 2 and all(n.major in (0, 1) for n in child.value)
                        and child.value[1].value > 0, 'invalid rational tag')
            value = (number, child)
        elif major == 7:
            require(ai in (20, 21, 22), 'unsupported CBOR simple or floating point')
            value = ai
        else:
            raise ValueError('unsupported CBOR major')
        return Node(major, value, start, pos, payload_start), pos

    result, end = read(0)
    require(end == len(raw), 'trailing CBOR bytes')
    return result


def array(n, size=None):
    require(n.major == 4 and (size is None or len(n.value) == size), 'array shape')
    return n.value


def mapping(n):
    require(n.major == 5, 'map shape')
    return list(zip(n.value[::2], n.value[1::2]))


def integer(n, signed=False):
    require(n.major in ((0, 1) if signed else (0,)), 'integer shape/sign')
    return n.value


def byte_string(n, size=None):
    require(n.major == 2 and (size is None or len(n.value) == size), 'bytes shape/length')
    return n.value


def txin(n):
    tid, idx = array(n, 2)
    index = integer(idx)
    require(index <= 65535, 'input index range')
    return byte_string(tid, 32).hex() + ':' + str(index)


def assets(n, signed=False):
    result = {}
    for policy, names in mapping(n):
        p = byte_string(policy, 28).hex()
        for name, quantity in mapping(names):
            b = byte_string(name)
            require(len(b) <= 32, 'asset name range')
            q = integer(quantity, signed)
            if signed:
                require(-(1 << 63) <= q < (1 << 63), 'mint quantity outside signed Int64')
            result[p + '.' + b.hex()] = q
    return result


def value(n):
    if n.major == 0:
        return {'lovelace': integer(n)}
    coin, multiasset = array(n, 2)
    return {'lovelace': integer(coin), **assets(multiasset)}


def output(n):
    # These corpus outputs are exclusively two-item legacy outputs. Reject extensions.
    address, amount = array(n, 2)
    a = byte_string(address)
    require(len(a) in (29, 57), 'unsupported output address')
    return value(amount)


def at(n, path):
    for index in path:
        n = array(n)[index]
    return n


def state_utxo(root, state_index):
    state = at(root, [state_index])
    array(state, 7)
    require(integer(at(state, [0])) == 899, 'epoch changed')
    epoch = at(state, [3]); array(epoch, 4)
    ledger = at(epoch, [1]); array(ledger, 2)
    utxo_state = at(ledger, [1]); array(utxo_state, 6)
    governance = at(utxo_state, [3]); array(governance, 7)
    require(byte_string(at(governance, [3]), 32).hex() == PPHASH, 'current parameters mismatch')
    result, ranges = {}, {}
    for key, out in mapping(at(utxo_state, [0])):
        k = txin(key)
        require(k not in result, 'duplicate input identity')
        result[k] = output(out)
        ranges[k] = {'file': 'raw sequence', 'output_range': [out.start, out.end],
                     'value_range': [array(out, 2)[1].start, array(out, 2)[1].end]}
    return result, ranges


def add(values):
    result = {}
    for v in values:
        for asset, q in v.items():
            result[asset] = result.get(asset, 0) + q
    return {k: v for k, v in result.items() if v != 0}


def quantities(v):
    return {k: str(v[k]) for k in sorted(v)}


def head(major, n):
    require(n >= 0, 'negative CBOR argument')
    if n < 24:
        return bytes([(major << 5) | n])
    for ai, width in ((24, 1), (25, 2), (26, 4), (27, 8)):
        if n < 1 << (8 * width):
            return bytes([(major << 5) | ai]) + n.to_bytes(width, 'big')
    raise ValueError('CBOR integer exceeds uint64')


def enc_bytes(b):
    return head(2, len(b)) + b


def enc_array(xs):
    return head(4, len(xs)) + b''.join(xs)


def enc_map(pairs):
    pairs = sorted(pairs, key=lambda pair: (len(pair[0]), pair[0]))
    return head(5, len(pairs)) + b''.join(k + v for k, v in pairs)


def enc_value(v):
    coin = head(0, v['lovelace'])
    policies = {}
    for asset, q in v.items():
        if asset != 'lovelace':
            policy, name = asset.split('.')
            policies.setdefault(policy, []).append((enc_bytes(bytes.fromhex(name)), head(0, q)))
    if not policies:
        return coin
    return enc_array([coin, enc_map([(enc_bytes(bytes.fromhex(p)), enc_map(names))
                                      for p, names in policies.items()])])


def enc_resolved(inputs, utxo):
    pairs = []
    for key in inputs:
        tid, idx = key.split(':')
        pairs.append((enc_array([enc_bytes(bytes.fromhex(tid)), head(0, int(idx))]), enc_value(utxo[key])))
    return enc_map(pairs)


def checked_file(path, digest):
    data = path.read_bytes()
    require(hashlib.sha256(data).hexdigest() == digest, 'immutable source changed: ' + str(path))
    return data


def project():
    for filename, digest in PINNED_FILES.items():
        checked_file(ROOT / filename, digest)
    checked_file(BASE / 'upstream/vectors.tar.gz', ARCHIVE_HASH)
    pp = checked_file(BASE / 'raw/pparams.cbor', PPHASH)
    params = decode(pp)
    array(params, 31)
    require([integer(n) for n in array(at(params, [12]), 2)] == [9, 0], 'unsupported protocol')
    rows, tsv = [], ['# name\ttxhex\tresolvedInputsCBORhex\texpectedBoolean']
    with tarfile.open(BASE / 'upstream/vectors.tar.gz', 'r:gz') as archive:
        members = archive.getmembers()
        require(len({m.name for m in members}) == len(members), 'duplicate archive members')
        for sequence, (digest, suite) in SOURCES.items():
            raw = checked_file(BASE / ('raw/' + sequence + '.cbor'), digest)
            title = 'Conway/Imp/' + suite + '/UTXO/ShelleyUtxoPredFailure/ValueNotConservedUTxO'
            member = 'eras/conway/impl/dump/' + title
            require(archive.extractfile(member).read() == raw, 'archive/Amaru byte mismatch')
            require(archive.extractfile('eras/conway/impl/dump/pparams-by-hash/' + PPHASH).read() == pp,
                    'archive parameter byte mismatch')
            root = decode(raw); array(root, 5)
            config = array(at(root, [0]), 13)
            require(integer(config[0]) == 3883680 and integer(config[1]) == 899, 'config changed')
            require(at(root, [4]).major == 3 and at(root, [4]).value.decode() == title, 'era/title changed')
            events = array(at(root, [3]), 3)
            require([integer(n) for n in array(events[0], 2)] == [1, 1], 'unsupported tick')
            utxo, origins = state_utxo(root, 1)
            for event_index, event in enumerate(events[1:], 1):
                kind, payload, status, slot = array(event, 4)
                require(integer(kind) == 0 and integer(slot) == 3883681, 'event kind/slot changed')
                require(status.major == 7 and status.value in (20, 21), 'nonboolean expected status')
                expected = status.value == 21
                require(expected == (event_index == 1), 'unexpected sequence statuses')
                txraw = byte_string(payload)
                tx = decode(txraw)
                body, witnesses, is_valid, auxiliary = array(tx, 4)
                require(is_valid.major == 7 and is_valid.value == 21, 'collateral/invalid path unsupported')
                require(auxiliary.major == 7 and auxiliary.value == 22, 'auxiliary data unsupported')
                mapping(witnesses)  # Preserve all witness bytes; not a witness validator.
                fields = {integer(k): v for k, v in mapping(body)}
                require(set(fields) in ({0, 1, 2}, {0, 1, 2, 9}),
                        'unsupported body fields (certificates/withdrawals/governance/donation/collateral/etc)')
                input_node = fields[0]
                require(input_node.major == 6 and input_node.value[0] == 258, 'input set encoding changed')
                ins = [txin(n) for n in array(input_node.value[1])]
                require(len(ins) > 0 and len(ins) == len(set(ins)), 'empty/duplicate spending inputs')
                require(all(i in utxo for i in ins), 'unresolved spending input')
                outnodes = array(fields[1])
                outs = [output(n) for n in outnodes]
                fee = integer(fields[2])
                mint = assets(fields[9], signed=True) if 9 in fields else {}
                consumed = add([utxo[i] for i in ins] + [{k: q for k, q in mint.items() if q > 0}])
                produced = add(outs + [{'lovelace': fee}, {k: -q for k, q in mint.items() if q < 0}])
                require((consumed == produced) == expected, 'calculated predicate disagrees with upstream status')
                name = 'conway-pv9-' + sequence + ('-accept' if expected else '-reject')
                resolved = enc_resolved(ins, utxo)
                decode(resolved)
                txid = hashlib.blake2b(txraw[body.start:body.end], digest_size=32).hexdigest()
                tx_start = payload.payload_start
                row = {
                    'name': name, 'era': 'Conway', 'protocol_version': [9, 0], 'sequence': sequence,
                    'source_sha256': digest, 'archive_member': member, 'title': title,
                    'event_index': event_index, 'slot': str(integer(slot)), 'epoch': '899',
                    'transaction_range': [tx_start, payload.end],
                    'body_range': [tx_start + body.start, tx_start + body.end],
                    'txid_blake2b256': txid, 'tx_cbor_hex': txraw.hex(),
                    'body_fields': sorted(fields), 'resolved_inputs_cbor_hex': resolved.hex(),
                    'resolved_inputs': [{'input': i, 'value': quantities(utxo[i]), 'origin': origins[i]} for i in ins],
                    'outputs': [{'value': quantities(v), 'raw_range': [tx_start + n.start, tx_start + n.end]}
                                for v, n in zip(outs, outnodes)],
                    'fee': str(fee), 'mint': quantities(mint), 'expected_predicate_pass': expected,
                    'expected_source': 'Haskell LEDGER boolean; rejection family asserted by pinned Haskell source',
                    'calculated_consumed': quantities(consumed), 'calculated_produced': quantities(produced),
                    'calculated_delta': quantities(add([consumed, {k: -q for k, q in produced.items()}])),
                }
                rows.append(row)
                tsv.append('\t'.join([name, txraw.hex(), resolved.hex(), str(expected).lower()]))
                if expected:
                    for i in ins:
                        del utxo[i]; del origins[i]
                    for i, (v, outnode) in enumerate(zip(outs, outnodes)):
                        key = txid + ':' + str(i)
                        require(key not in utxo, 'setup output collision')
                        utxo[key] = v
                        amount = array(outnode, 2)[1]
                        origins[key] = {'file': 'raw sequence', 'event_index': event_index,
                                        'output_range': [tx_start + outnode.start, tx_start + outnode.end],
                                        'value_range': [tx_start + amount.start, tx_start + amount.end]}
            final, _ = state_utxo(root, 2)
            require(final == utxo, 'final UTxO value projection mismatch')
    require(len(rows) == 4 and len({r['name'] for r in rows}) == 4, 'fixture count/name mismatch')
    return {'ledger-vectors.tsv': ('\n'.join(tsv) + '\n').encode(),
            'projection.json': (json.dumps(rows, indent=2, sort_keys=True) + '\n').encode()}


def self_test():
    # Definite length/integer 31 must not be mistaken for additional-info 31.
    require(decode(bytes.fromhex('181f')).value == 31, 'integer 31 regression')
    require(len(decode(bytes.fromhex('581f') + bytes(31)).value) == 31, 'bytes length 31 regression')
    require(len(array(decode(bytes.fromhex('981f') + bytes(31)))) == 31, 'array length 31 regression')
    require(len(array(decode(bytes.fromhex('9f0102ff')))) == 2, 'indefinite array regression')
    require(len(mapping(decode(bytes.fromhex('bf0102ff')))) == 1, 'indefinite map regression')
    bad = ['', '18', '1900', '582000', '81', '9f01', 'bf01ff', 'a201000102',
           'a20100180102', '1f', '3f', '5f40ff', '7f60ff', 'df00', 'ff', '0000',
           '1c', 'f7', 'f800', 'fa00000000', 'c000', 'd9010200', '61ff']
    for source in bad:
        try:
            decode(bytes.fromhex(source))
        except (ValueError, UnicodeError):
            continue
        raise ValueError('negative parser test unexpectedly accepted: ' + source)
    for fn, encoded in ((integer, '20'), (txin, '82410000'),
                        (txin, '825820' + '00' * 32 + '1a00010000'),
                        (value, '8220a0'), (assets, 'a14100a0'),
                        (output, '83411d0000')):
        try:
            fn(decode(bytes.fromhex(encoded)))
        except ValueError:
            continue
        raise ValueError('negative projection-shape test unexpectedly accepted: ' + encoded)
    policy = '581c' + '00' * 28
    for q in ('1b8000000000000000', '3b8000000000000000'):
        try:
            assets(decode(bytes.fromhex('a1' + policy + 'a140' + q)), signed=True)
        except ValueError:
            continue
        raise ValueError('mint quantity outside Int64 accepted')
    for q in ('1b7fffffffffffffff', '3b7fffffffffffffff'):
        assets(decode(bytes.fromhex('a1' + policy + 'a140' + q)), signed=True)
    # Semantic input duplication, unsupported fields and negative amounts are checked
    # above separately from generic CBOR syntax. Duplicate array-key maps also fail.
    try:
        decode(bytes.fromhex('a2810000810001'))
    except ValueError:
        pass
    else:
        raise ValueError('duplicate compound map key accepted')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    self_test()
    artifacts = project()
    for filename, content in artifacts.items():
        target = BASE / filename
        if args.check:
            require(target.read_bytes() == content, 'derived artifact differs: ' + filename)
        else:
            target.write_bytes(content)
        print(hashlib.sha256(content).hexdigest(), filename)
    print('4 Haskell-derived Conway PV9 cases; parser self-tests and final value projections verified')


if __name__ == '__main__':
    main()
