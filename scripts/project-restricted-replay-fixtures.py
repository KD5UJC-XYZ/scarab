#!/usr/bin/env python3
"""Offline immutable two-trace extraction. No signing, reference execution or network.

The trace inputs contain no expected acceptance flag or per-transaction resolution.
Archived outcomes/final output bytes and source-derived fees live in separate evidence.
"""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
MAX_FILE = 2_000_000
ARCHIVE_HASH = '33de88ffd3fe1f82326704056264bfcbd62231079e1f88b67fc60eb74da10e40'
DECODER_HASH = '40902e603d51b07395b1df1ae661fe486c68ed5d20904032c0d2b93e4cee7ade'
PPHASH = '23a62c9e17bfb1c9755134f173e29e7ff87f9991a84b5bb40ec177b262647633'
INITIAL_HASH = '3403e98e8bbdb656b05ce8082a78a0c60670ed2466696a494c5eff5b7a834896'
TRACES = (
    ('value-conservation', 'fixtures/ledger/raw/shelley.cbor',
     'e29fa93400521ce6b15b357a25ed61afdc81e8fd18cd15ebbc29f16e30019123',
     'Conway/Imp/ShelleyImpSpec/UTXO/ShelleyUtxoPredFailure/ValueNotConservedUTxO',
     'balance', 'fixtures/ledger/upstream/amaru-fork-ShelleyUtxoSpec.hs',
     '25724216d08356d839849bc9f4a58f2435dc9060181850a494876bcf15cd874a'),
    ('missing-vkey', 'fixtures/coverage/raw/missing-vkey.cbor',
     '3f63e5d5235c06cf8332ed5f154cecc3248fc0c630cf58969afb515b6115d2a1',
     'Conway/Imp/ShelleyImpSpec/UTXOW/MissingVKeyWitnessesUTXOW',
     'coverage', 'fixtures/coverage/upstream/generator-UtxowSpec.hs',
     '9643941f4b04d597c3478e86b2498ad65c26292a95bc1aee2bf45524db7f5b3d'))


def require(ok, message):
    if not ok:
        raise ValueError(message)


def read(path, limit=MAX_FILE):
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    require(len(data) <= limit, 'input byte bound: ' + str(path))
    return data


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def pinned(root, name, expected):
    data = read(root / name)
    require(sha(data) == expected, 'immutable source mismatch: ' + name)
    return data


def encoded(value):
    return (json.dumps(value, indent=2, sort_keys=True) + '\n').encode()


def project(root):
    pp = pinned(root, 'fixtures/ledger/raw/pparams.cbor', PPHASH)
    archive = pinned(root, 'fixtures/ledger/upstream/vectors.tar.gz', ARCHIVE_HASH)
    pinned(root, 'scripts/project-ledger-fixtures.py', DECODER_HASH)
    raws = {}
    for name, file, digest, title, stage, source, source_hash in TRACES:
        raws[name] = pinned(root, file, digest)
        pinned(root, source, source_hash)
    targets = {'eras/conway/impl/dump/' + row[3]: raws[row[0]] for row in TRACES}
    targets['eras/conway/impl/dump/pparams-by-hash/' + PPHASH] = pp
    seen, found, total = set(), set(), 0
    with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as tar:
        for member in tar:
            require(len(seen) < 10000 and member.name not in seen, 'duplicate/excess archive members')
            seen.add(member.name)
            require(0 <= member.size <= 16_000_000, 'archive member size bound')
            total += member.size
            require(total <= 128_000_000, 'archive total size bound')
            if member.name in targets:
                expected = targets[member.name]
                require(member.isfile() and member.size == len(expected), 'archive target shape')
                require(tar.extractfile(member).read(len(expected) + 1) == expected, 'archive target bytes')
                found.add(member.name)
    require(found == set(targets), 'missing archive target')
    spec = importlib.util.spec_from_file_location('restricted_replay_decoder', root / 'scripts/project-ledger-fixtures.py')
    d = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = d
    spec.loader.exec_module(d)
    d.self_test()
    params = d.array(d.decode(pp), 31)
    require([d.integer(n) for n in d.array(params[12], 2)] == [9, 0], 'protocol version')
    require([d.integer(params[i]) for i in (0, 1, 3)] == [44, 155381, 16384], 'parameter record')
    artifacts = {'pparams.cbor': pp}
    evidence, origins, initial_maps = [], [], []
    extracted = {}

    def checked_output(node, raw, file):
        address, amount = d.array(node, 2)
        address = d.byte_string(address)
        require(address and (address[0] & 15) in (0, 1), 'address network nibble')
        require((address[0] >> 4, len(address)) in ((0, 57), (6, 29)), 'key payment address')
        require(amount.major == 0, 'scalar coin only')
        d.integer(amount)
        exact = raw[node.start:node.end]
        require(d.decode(exact).identity() == node.identity(), 'output source span')
        return {'output_cbor_hex': exact.hex(), 'output_sha256': sha(exact),
                'source_file': file, 'source_byte_range': [node.start, node.end]}

    for name, file, digest, title, failure_stage, source, source_hash in TRACES:
        raw = raws[name]
        root_node = d.decode(raw)
        d.array(root_node, 5)
        config = d.array(d.at(root_node, [0]), 13)
        require([d.integer(n) for n in config[:2]] == [3883680, 899], 'config slot/epoch')
        title_node = d.at(root_node, [4])
        require(title_node.major == 3 and title_node.value.decode() == title, 'sequence title')
        def state(index):
            d.state_utxo(root_node, index)
            node = d.at(root_node, [index, 3, 1, 1, 0])
            outputs = {d.txin(k): checked_output(v, raw, file) for k, v in d.mapping(node)}
            require(0 < len(outputs) <= 4096, 'state entry cap')
            return raw[node.start:node.end], outputs, [node.start, node.end]
        initial_raw, initial, initial_range = state(1)
        final_raw, final, final_range = state(2)
        require(sha(initial_raw) == INITIAL_HASH, 'initial UTxO identity')
        initial_maps.append(initial_raw)
        events = d.array(d.at(root_node, [3]), 3)
        require([d.integer(n) for n in d.array(events[0], 2)] == [1, 1], 'recorded tick event')
        transactions, event_evidence = [], []
        projected = dict(initial)
        for index, event in enumerate(events[1:], 1):
            kind, payload, status, slot = d.array(event, 4)
            require(d.integer(kind) == 0 and d.integer(slot) == 3883681, 'transaction event kind/slot')
            require(status.major == 7 and status.value == (21 if index == 1 else 20), 'archived event outcome')
            txraw = d.byte_string(payload)
            require(raw[payload.payload_start:payload.end] == txraw, 'transaction source span')
            body, wit, valid, aux = d.array(d.decode(txraw), 4)
            require(valid.major == 7 and valid.value == 21 and aux.major == 7 and aux.value == 22, 'envelope scope')
            fields = {d.integer(k): v for k, v in d.mapping(body)}
            require(set(fields) == {0, 1, 2}, 'body scope')
            require({d.integer(k) for k, _ in d.mapping(wit)} <= {0}, 'witness scope')
            ins = fields[0]
            require(ins.major == 6 and ins.value[0] == 258, 'input set encoding')
            refs = [d.txin(n) for n in d.array(ins.value[1])]
            require(refs and len(refs) == len(set(refs)) and all(ref in projected for ref in refs), 'evolving source input resolution')
            fee = d.integer(fields[2])
            txid = hashlib.blake2b(txraw[body.start:body.end], digest_size=32).hexdigest()
            outs = [checked_output(n, txraw, f'fixtures/restricted-replay/{name}-{index}.cbor') for n in d.array(fields[1])]
            require(1 <= len(outs) <= 4096, 'output count')
            artifacts[f'{name}-{index}.cbor'] = txraw
            transactions.append(txraw)
            event_evidence.append({'event_index': index, 'archived_whole_ledger_success': index == 1,
                'expected_local_stage': 'applied' if index == 1 else failure_stage,
                'transaction_sha256': sha(txraw), 'transaction_id': txid,
                'source_byte_range': [payload.payload_start, payload.end],
                'body_byte_range_in_transaction': [body.start, body.end],
                'source_derived_supplied_fee': str(fee)})
            # This extraction-only replay follows known archive outcomes, never used for runtime decisions.
            if index == 1:
                require(fee == 167041, 'accepted setup fee')
                for ref in refs:
                    del projected[ref]
                for i, out in enumerate(outs):
                    ref = txid + ':' + str(i)
                    require(ref not in projected, 'source output collision')
                    projected[ref] = out
        require({k: v['output_cbor_hex'] for k, v in projected.items()} ==
                {k: v['output_cbor_hex'] for k, v in final.items()}, 'independent exact final output mismatch')
        require(len(final) == 2, 'final output count')
        artifacts[f'{name}-final-utxo.cbor'] = final_raw
        extracted[name] = transactions
        origins.append({'trace': name, 'source_file': file, 'source_sha256': digest,
            'archive_member': 'eras/conway/impl/dump/' + title, 'initial_utxo_range': initial_range,
            'final_utxo_range': final_range, 'transaction_ranges': [e['source_byte_range'] for e in event_evidence],
            'recorded_tick': {'kind': 1, 'value': 1, 'execution': 'RecordedButNotExecuted'}})
        evidence.append({'trace': name, 'events': event_evidence,
            'failure_source_file': source, 'failure_source_sha256': source_hash,
            'final_utxo': [{'input': ref, **out} for ref, out in sorted(final.items())],
            'exact_final_output_comparison': True,
            'source_derived_fee_accumulator': {'origin': '0', 'after_setup': '167041', 'after_rejection': '167041'},
            'raw_state_cell_observation': {'path': '[state][3][1][1][2]',
                'initial': str(d.integer(d.at(root_node, [1, 3, 1, 1, 2]))),
                'final': str(d.integer(d.at(root_node, [2, 3, 1, 1, 2]))),
                'semantics': 'unclassified; not an independently established fee-pot golden'}})
    require(initial_maps[0] == initial_maps[1], 'different initial UTxO byte maps')
    provenance = encoded({'schema': 'restricted-replay-attribution-v1', 'archive_sha256': ARCHIVE_HASH,
        'decoder_sha256': DECODER_HASH, 'parameters_sha256': PPHASH,
        'initial_utxo_sha256': INITIAL_HASH, 'initial_slot': '3883680', 'initial_epoch': '899',
        'research_slot': '3883681', 'traces': origins,
        'scope': 'Archived transaction traces, not Cardano blocks; tick recorded but not executed.'})
    artifacts['attribution.json'] = provenance
    artifacts['initial-utxo.cbor'] = initial_maps[0]
    artifacts['expectations.json'] = encoded({'schema': 'restricted-replay-test-expectations-v1',
        'scope': 'Archived output bytes/outcomes and separately source-derived fee arithmetic; no fresh Haskell execution.',
        'traces': evidence})
    header = '\t'.join(['restricted-replay-v1', 'conway-pv9-transfer-projection-v1', '3883681',
                        pp.hex(), initial_maps[0].hex(), sha(provenance)])
    for name, transactions in extracted.items():
        artifacts[name + '.trace.tsv'] = (header + '\n' + ''.join('tx\t' + tx.hex() + '\n' for tx in transactions)).encode()
    artifacts['SHA256SUMS'] = ''.join(sha(raw) + '  ' + name + '\n' for name, raw in sorted(artifacts.items())).encode()
    return artifacts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'fixtures/restricted-replay')
    args = parser.parse_args()
    artifacts = project(args.source_root)
    if not args.check:
        args.output_dir.mkdir(parents=True, exist_ok=True)
    for name, raw in sorted(artifacts.items()):
        path = args.output_dir / name
        if args.check:
            require(read(path) == raw, 'derived artifact mismatch: ' + name)
        else:
            path.write_bytes(raw)
        print(sha(raw), name)
    print('Two pinned traces; exact independent final output bytes; runtime inputs omit expected outcomes.')


if __name__ == '__main__':
    main()
