#!/usr/bin/env python3
"""Offline pinned fee/size fixture projection. Run anywhere; --check never writes.

Replays only archived accepted transfers, checks exact final output bytes, retains
original body/witness/output bytes. Synthetic expectations are not ledger goldens.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'fixtures/fee-size'
DECODER_HASH = '40902e603d51b07395b1df1ae661fe486c68ed5d20904032c0d2b93e4cee7ade'
ARCHIVE_HASH = '33de88ffd3fe1f82326704056264bfcbd62231079e1f88b67fc60eb74da10e40'
RAW_HASH = 'e29fa93400521ce6b15b357a25ed61afdc81e8fd18cd15ebbc29f16e30019123'
PPHASH = '23a62c9e17bfb1c9755134f173e29e7ff87f9991a84b5bb40ec177b262647633'
TITLE = 'Conway/Imp/ShelleyImpSpec/UTXO/ShelleyUtxoPredFailure/ValueNotConservedUTxO'
MEMBER = 'eras/conway/impl/dump/' + TITLE
# Immutable evidence pins independent of the editable checksum manifest.
PINNED_EVIDENCE = {'fixtures/fee-size/licenses/api-LICENSE': '0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594',
 'fixtures/fee-size/licenses/blueprint-LICENSE': '5bdd73433593173b55328fdc4cc01e826597e73cf52f93388a0ee786d39067cf',
 'fixtures/fee-size/licenses/generator-LICENSE': '0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594',
 'fixtures/fee-size/licenses/generator-NOTICE': '58721f8b6ca67f0fcbe1cd739b384fec3126a35f7d79951aedaa2bc3863a3162',
 'fixtures/fee-size/licenses/target-LICENSE': '0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594',
 'fixtures/fee-size/oracle/body-indefinite-map.json': '284077c2344740c5a51286b5039b9aad6451c926777734d251bafde949b1bb7c',
 'fixtures/fee-size/oracle/body-wide-fee.json': 'd21d58d69b7e1779e066bddc9b0f83724e309768ad6c0fa2ea706f0725a6ab43',
 'fixtures/fee-size/oracle/cli-results.json': '23d5b07329e83c76baddbf6c0a8afb43cad64df0bd880ac4fe3e1e28454d2342',
 'fixtures/fee-size/oracle/original.json': 'cecb509352d80b39991b7a5679263772252d5e5107eab72574bda7ebdbca6c3a',
 'fixtures/fee-size/oracle/outer-indefinite.json': '7569ec8a38783c880255943e9e74b87ad962de4dd704b2665103847ebca9a3a5',
 'fixtures/fee-size/oracle/outer-wide-length.json': '5911e4d8ccbd8e09ca807b85458934337d5ac0a73c71dfc71c91212a09c5b0fa',
 'fixtures/fee-size/oracle/parameter-results.json': 'baecd18bb19315693ccf694e1535e032e48c647915d1198e03aa1fd5a6d1eb95',
 'fixtures/fee-size/oracle/pparams.json': '7c6c4c907d15806d25f4b250872b44af538094d1a210cf91164d1a9b9b3cbecf',
 'fixtures/fee-size/oracle/variant-results.json': '20e6f1c712b1c60a91baef7d3676f8413273d831589f5c85e4311b370bf5b967',
 'fixtures/fee-size/oracle/witness-indefinite-map.json': '20031485ccfc62a10c94f92ec9b5e651cbcb78c6681fdd430d086148737f7126',
 'fixtures/fee-size/oracle/witness-wide-map.json': '89f715f0d767f0455f487fce74d2f2471eebea7f1db351ed5349ade1e5bd82ec',
 'fixtures/fee-size/upstream/api-Fee.hs': '416d59c784cde146e793ac757d7c2f8ff7ecfeea414a516a9e92f5001e0b84f2',
 'fixtures/fee-size/upstream/source-provenance.json': '5317a06aa2a74a23e5f130fe9f292ce119db2f8d302b1ebdca87f3aa3d88198a',
 'fixtures/fee-size/upstream/target-Alonzo-Tx.hs': 'd4ebeaa8cb9269a4a3b88420b8f66474c0370b6a440a4b44e9ddff884fed0ed7',
 'fixtures/fee-size/upstream/target-Api-Tx.hs': '9b0ed77360556bdec389a8eb5727953425f6140af5f547297f920e000279fd76',
 'fixtures/fee-size/upstream/target-Conway-PParams.hs': 'ba8712e61f23f067988b7d585194321a954ccedea2191df65e7d72f736db26ab',
 'fixtures/fee-size/upstream/target-Conway-Tx.hs': '4dee4c3e717bb21721729afc008b49a39a2f9fcd8fb661e0436a5e6e494521ff',
 'fixtures/fee-size/upstream/target-Conway-UTxO.hs': '11d7c614cfdc16aff2c7f0e1d6f6b553657c298de08afa631b5173eb7dcd04be',
 'fixtures/fee-size/upstream/target-MemoBytes-Internal.hs': '78254f49dfbfa96694bc238e3a7daf9509200d9de8890b913554eee3da4c1da7',
 'fixtures/fee-size/upstream/target-MemoBytes.hs': 'e71b3061adb037a5df3654c47b589fe09fba8a18b9e0431e0317f2b4ff5a9f0b',
 'fixtures/fee-size/upstream/target-Shelley-Tx.hs': '8cc7205b51bfc2822ba294ee7a22b23fb4dbd71e886b9f6f7328ac789411373f',
 'fixtures/fee-size/upstream/target-Tools.hs': 'a09bb19df2a8397be679809f3fb38e889b4ce3cea67dcab77eef9cdfabf2cc15'}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def read(path, limit=2_000_000):
    with path.open('rb') as f:
        data = f.read(limit + 1)
    require(len(data) <= limit, 'input byte limit')
    return data


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def pinned(path, digest):
    data = read(path)
    require(sha(data) == digest, 'pinned input mismatch: ' + str(path))
    return data


def decoder():
    path = ROOT / 'scripts/project-ledger-fixtures.py'
    pinned(path, DECODER_HASH)
    spec = importlib.util.spec_from_file_location('fee_size_decoder', path)
    m = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = m
    spec.loader.exec_module(m)
    m.self_test()
    return m


def project():
    for name, digest in PINNED_EVIDENCE.items():
        pinned(ROOT / name, digest)
    d = decoder()
    pinned(ROOT / 'fixtures/ledger/upstream/vectors.tar.gz', ARCHIVE_HASH)
    raw = pinned(ROOT / 'fixtures/ledger/raw/shelley.cbor', RAW_HASH)
    pp = pinned(ROOT / 'fixtures/ledger/raw/pparams.cbor', PPHASH)
    with tarfile.open(ROOT / 'fixtures/ledger/upstream/vectors.tar.gz') as archive:
        members = archive.getmembers()
        require(len({m.name for m in members}) == len(members), 'duplicate archive member')
        for name, expected in ((MEMBER, raw), ('eras/conway/impl/dump/pparams-by-hash/' + PPHASH, pp)):
            member = archive.getmember(name)
            require(member.isfile() and member.size == len(expected), 'archive member shape')
            require(archive.extractfile(member).read(len(expected) + 1) == expected, 'archive bytes mismatch')
    root = d.decode(raw)
    d.array(root, 5)
    require(d.at(root, [4]).value.decode() == TITLE, 'fixture title mismatch')
    params = d.array(d.decode(pp), 31)
    require([d.integer(n) for n in d.array(params[12], 2)] == [9, 0], 'unsupported protocol')
    a, b, mx = [d.integer(params[i]) for i in (0, 1, 3)]
    require(0 <= a < 2**64 and 0 <= b < 2**64 and 0 <= mx < 2**32, 'parameter bounds')

    def checked_output(n, source, file):
        addr, _ = d.array(n, 2)
        address = d.byte_string(addr)
        require((address[0] >> 4, len(address)) in ((0, 57), (6, 29)), 'script/address scope')
        require((address[0] & 15) in (0, 1), 'network scope')
        d.output(n)
        exact = source[n.start:n.end]
        require(d.decode(exact).identity() == n.identity(), 'output range mismatch')
        return {'bytes': exact, 'file': file, 'range': [n.start, n.end]}

    def state(index):
        d.state_utxo(root, index)  # checks epoch and both current parameter hashes
        return {d.txin(k): checked_output(v, raw, 'fixtures/ledger/raw/shelley.cbor')
                for k, v in d.mapping(d.at(root, [index, 3, 1, 1, 0]))}

    def evidence(ref, out):
        return {'ref': ref, 'output_cbor_hex': out['bytes'].hex(), 'output_sha256': sha(out['bytes']),
                'source_file': out['file'], 'source_byte_range': out['range']}

    utxo = state(1)
    rows, output = [], {}
    tsv = ['# name\ttxhex\tresolvedOutputsHex\tfeePerByte\tfeeFixed\tmaxTxSize\tsourceDerivedSize\tarchivedLedgerSuccess']
    events = d.array(d.at(root, [3]), 3)
    require([d.integer(n) for n in d.array(events[0], 2)] == [1, 1], 'unsupported initial event')
    for index, event in enumerate(events[1:], 1):
        kind, payload, status, slot = d.array(event, 4)
        require(d.integer(kind) == 0 and d.integer(slot) == 3883681, 'event kind/slot')
        require(status.major == 7 and status.value == (21 if index == 1 else 20), 'archived status')
        accepted = status.value == 21
        txraw = d.byte_string(payload)
        body, wit, valid, aux = d.array(d.decode(txraw), 4)
        fields = {d.integer(k): v for k, v in d.mapping(body)}
        require(set(fields) == {0, 1, 2}, 'unsupported body scope')
        require({d.integer(k) for k, _ in d.mapping(wit)} <= {0}, 'unsupported witness scope')
        require(valid.major == 7 and valid.value == 21 and aux.major == 7 and aux.value == 22, 'envelope scope')
        inputs = fields[0]
        require(inputs.major == 6 and inputs.value[0] == 258, 'input set')
        refs = [d.txin(n) for n in d.array(inputs.value[1])]
        require(refs and len(refs) == len(set(refs)) and all(r in utxo for r in refs), 'input resolution')
        name = 'transfer-event-' + str(index)
        txfile = name + '.cbor'
        output[txfile] = txraw
        outs = [checked_output(n, txraw, 'fixtures/fee-size/' + txfile) for n in d.array(fields[1])]
        pairs = []
        for ref in refs:
            tid, ix = ref.split(':')
            pairs.append((d.enc_array([d.enc_bytes(bytes.fromhex(tid)), d.head(0, int(ix))]), utxo[ref]['bytes']))
        resolved = d.enc_map(pairs)
        d.decode(resolved)
        output[name + '.resolved.cbor'] = resolved
        sizebytes = b'\x83' + txraw[body.start:body.end] + txraw[wit.start:wit.end] + b'\xf6'
        size, fee = len(sizebytes), d.integer(fields[2])
        require(size in (265, 402) and fee == a * size + b and size <= mx, 'source-derived predicate')
        rows.append({'name': name, 'transaction_sha256': sha(txraw), 'transaction_source_range': [payload.payload_start, payload.end],
                     'component_ranges': {'body': [body.start, body.end], 'witnesses': [wit.start, wit.end]},
                     'resolved_inputs': [evidence(ref, utxo[ref]) for ref in refs],
                     'produced_outputs': [evidence(str(i), out) for i, out in enumerate(outs)],
                     'source_derived_size': size, 'size_encoding_hex': sizebytes.hex(),
                     'source_derived_minimum_fee': str(a * size + b), 'supplied_fee': str(fee),
                     'source_derived_fee_pass': True, 'source_derived_size_pass': True,
                     'archived_whole_ledger_success': accepted})
        tsv.append('\t'.join([name, txraw.hex(), resolved.hex(), str(a), str(b), str(mx), str(size), str(accepted).lower()]))
        if accepted:
            for ref in refs:
                del utxo[ref]
            txid = hashlib.blake2b(txraw[body.start:body.end], digest_size=32).hexdigest()
            for i, out in enumerate(outs):
                ref = txid + ':' + str(i)
                require(ref not in utxo, 'output collision')
                utxo[ref] = out
    final = state(2)
    require({k: v['bytes'] for k, v in utxo.items()} == {k: v['bytes'] for k, v in final.items()}, 'exact final UTxO mismatch')
    output['fee-size-vectors.tsv'] = ('\n'.join(tsv) + '\n').encode()
    output['projection.json'] = (json.dumps({'archive_sha256': ARCHIVE_HASH, 'member': MEMBER,
        'sequence_sha256': RAW_HASH, 'parameters_sha256': PPHASH,
        'parameters': {'fee_per_byte': str(a), 'fee_fixed': str(b), 'max_tx_size': str(mx)},
        'events': rows, 'final_utxo_exact_bytes_equal': True,
        'final_utxo': [evidence(k, v) for k, v in sorted(final.items())]}, indent=2, sort_keys=True) + '\n').encode()
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    artifacts = project()
    for name, content in sorted(artifacts.items()):
        path = BASE / name
        if args.check:
            require(read(path) == content, 'derived artifact mismatch: ' + name)
        else:
            path.write_bytes(content)
        print(sha(content), name)
    print('2 archived events; source-derived fee/size expectations; exact accepted-state replay verified')


if __name__ == '__main__':
    main()
