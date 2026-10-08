#!/usr/bin/env python3
"""Bounded offline projection of pinned Haskell MissingVKeyWitnessesUTXOW evidence.

Only reads public archived inputs. No binaries, native libraries, sockets, network,
key generation or signing. --check verifies all deterministic artifacts without writing.
"""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'fixtures/coverage'
MAX_FILE = 2_000_000
TITLE = 'Conway/Imp/ShelleyImpSpec/UTXOW/MissingVKeyWitnessesUTXOW'
MEMBER = 'eras/conway/impl/dump/' + TITLE
RAW_HASH = '3f63e5d5235c06cf8332ed5f154cecc3248fc0c630cf58969afb515b6115d2a1'
MISSING = '3c875ce0f647bdcc64b70e62814680fbd939f8faf0203b98236ede7b'
# Generated once from immutable source files; never trust fixture-supplied hashes.
PINNED_INPUTS = {'fixtures/coverage/README.md': 'd6f39057d805a4d4d231e6417e6f545a6493dd90db52cf50da476ff29ac86c49',
 'fixtures/coverage/oracle/check-public-oracles.py': '0180f0e396e51e54e81314141824996f45a3d6a8eada98b9aa7c85c52b60bc5c',
 'fixtures/coverage/oracle/cli-binary-provenance.json': '9aa6f53c7be7032116cd8aee9f372726290a5c7997a611fc8270ffcb34d94191',
 'fixtures/coverage/oracle/cli-keyhash-results.json': '7cdd2dea8bc5d3889cd92311ccb8f37c4b181477587a13476ef9305aff6ed63b',
 'fixtures/coverage/oracle/event-1-witness-0.vkey': 'e5e81642453febd88a52e5977b2c2c8cf346fa753017a2f4e83bd39522b7e993',
 'fixtures/coverage/oracle/event-2-witness-0.vkey': '57e95fb85096be865417b5f675413ab5f9b140bb59145554c720ec9a55919c6b',
 'fixtures/coverage/oracle/public-oracle-results.json': '2e651ed3e8122d6b16c3f2bb9475b81b6dd25ae162b17a8cc5f5de1fc0dd52cf',
 'fixtures/coverage/provenance.json': '429a64901aec7e16a8d33c7ec55512fc14376e22616c29e7587b89f1fb367440',
 'fixtures/coverage/raw/missing-vkey.cbor': '3f63e5d5235c06cf8332ed5f154cecc3248fc0c630cf58969afb515b6115d2a1',
 'fixtures/coverage/upstream/generator-UtxowSpec.hs': '9643941f4b04d597c3478e86b2498ad65c26292a95bc1aee2bf45524db7f5b3d',
 'fixtures/ledger/provenance.json': '8095da3727d1b93d9ff1a2fb73134189bd155566390ca2fb3488853aa092fe3c',
 'fixtures/ledger/raw/pparams.cbor': '23a62c9e17bfb1c9755134f173e29e7ff87f9991a84b5bb40ec177b262647633',
 'fixtures/ledger/upstream/amaru-blueprint-README.md': '22d2e95edafbb3612e49813d2cdcafa8c1afd28d176ce3fd4eea71dd4719f4fb',
 'fixtures/ledger/upstream/amaru-evaluate_ledger_states.rs': '560c38193e035e20b6ab87529842b90bf9a025fbcc9de68a44129365ddac5bbb',
 'fixtures/ledger/upstream/amaru-fork-MaryUtxoSpec.hs': '3d4e36b9b759a7bc75593290f71e657b71dc7fa58cdac119da59f4e51924aed6',
 'fixtures/ledger/upstream/amaru-fork-ShelleyImpTest.hs': '34c4138d24c9742a9963520f45e6c88b803c1743956b799832a8f358de8b4a42',
 'fixtures/ledger/upstream/amaru-fork-ShelleyUtxoSpec.hs': '25724216d08356d839849bc9f4a58f2435dc9060181850a494876bcf15cd874a',
 'fixtures/ledger/upstream/vectors.tar.gz': '33de88ffd3fe1f82326704056264bfcbd62231079e1f88b67fc60eb74da10e40',
 'fixtures/licenses/amaru-LICENSE': '59899c6091b540582ed617e8eeaac4919dc985ccfc35459ee9752b699be5205b',
 'fixtures/licenses/blueprint-conformance-LICENSE': '5bdd73433593173b55328fdc4cc01e826597e73cf52f93388a0ee786d39067cf',
 'fixtures/licenses/cardano-ledger-conformance-LICENSE': '0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594',
 'fixtures/licenses/cardano-ledger-conformance-NOTICE': '58721f8b6ca67f0fcbe1cd739b384fec3126a35f7d79951aedaa2bc3863a3162',
 'scripts/project-ledger-fixtures.py': '40902e603d51b07395b1df1ae661fe486c68ed5d20904032c0d2b93e4cee7ade'}
EXPECTED_OUTPUTS = {'conway-pv9-missing-vkey-accept.resolved.cbor.hex': 'cf86b91cf73e9511d2fa4d279a135c6903b689b7f417afa074fd485f6fd0090d',
 'conway-pv9-missing-vkey-accept.tx.cbor.hex': '057f7d0a3180c9081fa90129fd03c3afe54de5878dea77ab503b572a122caf78',
 'conway-pv9-missing-vkey-reject.resolved.cbor.hex': '7adba4943c23427f616c0d37129b43fcef6f44e7101c930bb679c99a53acf39f',
 'conway-pv9-missing-vkey-reject.tx.cbor.hex': '4337d3f94925528b1274370bd13763de31c8665710e14993db20a46bf7e916df',
 'coverage-vectors.tsv': 'a876e7ffd7bd00881a8e001a4a73151680f4d604ec426910ac9434dc3ecec4b4',
 'manifest.json': '2d13413a7a1777e527a009a22f1748456208b31f95c510c22571da60110ead98',
 'projection.json': '8b9f8756f111ef30259ae4fb6aca7f8636f41ddbc8e46b0d9e6c03cb95111a2c'}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def bounded_read(path, maximum=MAX_FILE):
    with path.open('rb') as stream:
        data = stream.read(maximum + 1)
    require(len(data) <= maximum, 'file exceeds byte limit: ' + str(path))
    return data


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + '\n').encode()


def read_inputs():
    inputs = {}
    for name, digest in PINNED_INPUTS.items():
        data = bounded_read(ROOT / name)
        require(hashlib.sha256(data).hexdigest() == digest, 'immutable source changed: ' + name)
        inputs[name] = data
    return inputs


def load_decoder(inputs):
    # Check dependency bytes before importing Python code from the repository.
    spec = importlib.util.spec_from_file_location('coverage_ledger_projector', ROOT / 'scripts/project-ledger-fixtures.py')
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    module.self_test()
    return module


def project(inputs, m):
    raw = inputs['fixtures/coverage/raw/missing-vkey.cbor']
    pp = inputs['fixtures/ledger/raw/pparams.cbor']
    # Iterate, rather than materializing an unbounded tar directory. No extraction to disk.
    selected = {}
    seen = set()
    total = 0
    targets = {MEMBER, 'eras/conway/impl/dump/pparams-by-hash/' + m.PPHASH}
    archive = inputs['fixtures/ledger/upstream/vectors.tar.gz']
    with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as tar:
        for member in tar:
            require(len(seen) < 10000 and member.name not in seen, 'duplicate/too many archive members')
            seen.add(member.name)
            require(0 <= member.size <= 16_000_000, 'archive member size limit')
            total += member.size
            require(total <= 128_000_000, 'archive total size limit')
            if member.name in targets:
                require(member.isfile() and member.size <= MAX_FILE, 'archive target type/size')
                content = tar.extractfile(member).read(MAX_FILE + 1)
                require(len(content) == member.size, 'archive target length')
                selected[member.name] = content
    require(set(selected) == targets and selected[MEMBER] == raw, 'archive sequence mismatch')
    require(selected['eras/conway/impl/dump/pparams-by-hash/' + m.PPHASH] == pp, 'archive parameters mismatch')
    params = m.decode(pp); m.array(params, 31)
    require([m.integer(n) for n in m.array(m.at(params, [12]), 2)] == [9, 0], 'protocol version')
    root = m.decode(raw); m.array(root, 5)
    config = m.array(m.at(root, [0]), 13)
    require([m.integer(n) for n in config[:2]] == [3883680, 899], 'configuration slot/epoch')
    title = m.at(root, [4])
    require(title.major == 3 and title.value.decode() == TITLE, 'Conway sequence title')
    # Includes epoch, complete state-path shapes, and both current-parameter hashes.
    for state_index in (1, 2):
        m.state_utxo(root, state_index)
    events = m.array(m.at(root, [3]), 3)
    require([m.integer(n) for n in m.array(events[0], 2)] == [1, 1], 'tick event')

    def output(n):
        address, amount = m.array(n, 2)
        address = m.byte_string(address)
        require(address and address[0] & 15 in (0, 1), 'address network')
        kind = address[0] >> 4
        require((kind == 0 and len(address) == 57) or (kind == 6 and len(address) == 29), 'transfer address profile')
        return address, m.value(amount)

    def sequence(n):
        require(n.major == 6 and n.value[0] == 258, 'expected set tag 258')
        values = m.array(n.value[1])
        require(len({v.identity() for v in values}) == len(values), 'duplicate set element')
        return values

    utxo = {}
    for key, out in m.mapping(m.at(root, [1, 3, 1, 1, 0])):
        ref = m.txin(key)
        require(ref not in utxo, 'duplicate UTxO identity')
        output(out)
        utxo[ref] = (out, 0)
    rows, artifacts = [], {}
    tsv = ['# name\ttxhex\tresolvedInputsCBORhex\texpectedCoverageBoolean']
    oracle = json.loads(inputs['fixtures/coverage/oracle/public-oracle-results.json'])
    cli = json.loads(inputs['fixtures/coverage/oracle/cli-keyhash-results.json'])
    require(len(oracle) == len(cli) == 2, 'public oracle row count')
    for event_index, event in enumerate(events[1:], 1):
        kind, payload, status, slot = m.array(event, 4)
        require(m.integer(kind) == 0 and m.integer(slot) == 3883681, 'transaction event kind/slot')
        require(status.major == 7 and status.value == (21 if event_index == 1 else 20), 'Haskell expected boolean')
        accepted = status.value == 21
        txraw = m.byte_string(payload)
        body, witness_map, valid, aux = m.array(m.decode(txraw), 4)
        require(valid.major == 7 and valid.value == 21 and aux.major == 7 and aux.value == 22, 'valid/auxiliary profile')
        fields = {m.integer(k): v for k, v in m.mapping(body)}
        witnesses = {m.integer(k): v for k, v in m.mapping(witness_map)}
        require(set(fields) == {0, 1, 2} and set(witnesses) == {0}, 'closed transfer body/witness profile')
        refs = [m.txin(n) for n in sequence(fields[0])]
        require(refs and len(refs) == len(set(refs)), 'empty/duplicate spending inputs')
        require(all(ref in utxo for ref in refs), 'unresolved spending inputs')
        outs = m.array(fields[1]); [output(n) for n in outs]
        m.integer(fields[2])
        resolved, needed, pairs = [], set(), []
        for ref in refs:
            out, origin = utxo[ref]
            address, amount = output(out)
            key_hash = address[1:29].hex(); needed.add(key_hash)
            start, end = origin + out.start, origin + out.end
            outbytes = raw[start:end]
            require(m.decode(outbytes).identity() == out.identity(), 'resolved source byte range')
            tid, index = ref.split(':')
            key = m.enc_array([m.enc_bytes(bytes.fromhex(tid)), m.head(0, int(index))])
            pairs.append((key, outbytes))
            resolved.append({'input': ref, 'address_hex': address.hex(), 'payment_key_hash': key_hash,
                             'value': m.quantities(amount), 'output_range': [start, end], 'output_cbor_hex': outbytes.hex()})
        provided = []
        for witness in sequence(witnesses[0]):
            pk, sig = m.array(witness, 2)
            pk, sig = m.byte_string(pk, 32), m.byte_string(sig, 64)
            provided.append({'public_key_hex': pk.hex(), 'signature_hex': sig.hex(),
                             'key_hash_blake2b224': hashlib.blake2b(pk, digest_size=28).hexdigest()})
        require(len(provided) == 1, 'expected single provided witness')
        missing = sorted(needed - {w['key_hash_blake2b224'] for w in provided})
        require(missing == ([] if accepted else [MISSING]), 'reconstructed missing-key diagnostic')
        observed = oracle[event_index - 1]; cli_row = cli[event_index - 1]
        kh = provided[0]['key_hash_blake2b224']
        for observation in (observed, cli_row):
            require(observation['event'] == event_index and observation['exit_code'] == 0 and
                    observation['stdout'] == kh + '\n' and observation['stderr'] == '' and
                    observation['matches'] is True and observation['expected_blake2b224'] == kh,
                    'archived CLI observation differs from raw public key hash')
        require(observed['pinned_sodium_rc'] == 0 and observed['signature_verified'] is True,
                'archived signature observation')
        envelope = json.loads(inputs[f'fixtures/coverage/oracle/event-{event_index}-witness-0.vkey'])
        require(envelope['type'] == 'PaymentVerificationKeyShelley_ed25519' and
                envelope['cborHex'] == '5820' + provided[0]['public_key_hex'], 'public envelope mismatch')
        resolved_bytes = m.enc_map(pairs); m.decode(resolved_bytes)
        bodybytes = txraw[body.start:body.end]
        txid = hashlib.blake2b(bodybytes, digest_size=32).hexdigest()
        name = 'conway-pv9-missing-vkey-' + ('accept' if accepted else 'reject')
        for suffix, content in (('tx.cbor.hex', txraw), ('resolved.cbor.hex', resolved_bytes)):
            artifacts[name + '.' + suffix] = (content.hex() + '\n').encode()
        row = {'name': name, 'era': 'Conway', 'protocol_version': [9, 0], 'event_index': event_index,
               'slot': '3883681', 'epoch': '899', 'archive_member': MEMBER, 'source_sha256': RAW_HASH,
               'transaction_range': [payload.payload_start, payload.end],
               'body_range': [payload.payload_start + body.start, payload.payload_start + body.end],
               'txid_blake2b256': txid, 'body_cbor_hex': bodybytes.hex(), 'tx_cbor_hex': txraw.hex(),
               'body_fields': sorted(fields), 'witness_fields': sorted(witnesses),
               'resolved_inputs_cbor_hex': resolved_bytes.hex(), 'resolved_inputs': resolved,
               'needed_key_hashes': sorted(needed), 'provided_witnesses': provided, 'missing_key_hashes': missing,
               'expected_coverage_pass': accepted, 'source_ledger_expected_success': accepted,
               'expected_provided_signatures_verify': True,
               'diagnostic_evidence': 'Reconstructed hashes; archive stores LEDGER boolean, not serialized failure payload',
               'signature_evidence': 'Archived separate public-only pinned-sodium check, not rerun by this projector'}
        rows.append(row)
        tsv.append('\t'.join((name, txraw.hex(), resolved_bytes.hex(), str(accepted).lower())))
        if accepted:
            for ref in refs:
                del utxo[ref]
            for index, out in enumerate(outs):
                ref = txid + ':' + str(index)
                require(ref not in utxo, 'output collision')
                utxo[ref] = (out, payload.payload_start)
    final = {}
    for key, out in m.mapping(m.at(root, [2, 3, 1, 1, 0])):
        ref = m.txin(key); require(ref not in final, 'duplicate final UTxO identity')
        output(out); final[ref] = out.identity()
    require({k: out.identity() for k, (out, _) in utxo.items()} == final,
            'final full UTxO address/value projection mismatch')
    artifacts['projection.json'] = json_bytes(rows)
    artifacts['coverage-vectors.tsv'] = ('\n'.join(tsv) + '\n').encode()
    artifacts['manifest.json'] = json_bytes({'schema_version': 1, 'era': 'Conway', 'protocol_version': [9, 0],
        'cases': [{'name': r['name'], 'transaction': r['name'] + '.tx.cbor.hex',
                   'resolved_inputs': r['name'] + '.resolved.cbor.hex',
                   'expected_coverage_pass': r['expected_coverage_pass'],
                   'expected_provided_signatures_verify': True} for r in rows],
        'resolved_schema': 'CBOR map [txid bytes32,index uint16] -> full original [address bytes,value] output',
        'final_utxo_addresses_and_values_match': True,
        'artifacts_sha256': {name: hashlib.sha256(data).hexdigest() for name, data in sorted(artifacts.items())}})
    return artifacts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    require(not sys.flags.optimize, 'Do not disable validation with -O')
    inputs = read_inputs()
    artifacts = project(inputs, load_decoder(inputs))
    require(set(artifacts) == set(EXPECTED_OUTPUTS), 'output manifest names')
    for name, data in artifacts.items():
        require(hashlib.sha256(data).hexdigest() == EXPECTED_OUTPUTS[name], 'fixed artifact hash: ' + name)
    local_inputs = {str(Path(name).relative_to('fixtures/coverage')): data for name, data in inputs.items()
                    if name.startswith('fixtures/coverage/')}
    known = set(local_inputs) | set(artifacts) | {'SHA256SUMS'}
    actual = {str(p.relative_to(BASE)) for p in BASE.rglob('*') if p.is_file()}
    require(actual <= known, 'unrecognized coverage fixture file')
    sums = ''.join(hashlib.sha256(data).hexdigest() + '  ' + name + '\n'
                   for name, data in sorted({**local_inputs, **artifacts}.items())).encode()
    for name, data in {**artifacts, 'SHA256SUMS': sums}.items():
        if args.check:
            require(bounded_read(BASE / name) == data, 'derived artifact differs: ' + name)
        else:
            (BASE / name).write_bytes(data)
    print('Verified 2 Haskell-derived Conway PV9 coverage cases; full final UTxO; 2 archived public hash/signature observations.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, IndexError, tarfile.TarError) as error:
        print('coverage projection failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
