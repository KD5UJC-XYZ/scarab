# SPDX-License-Identifier: Apache-2.0
"""Finite, reproducible API matrix. Derived bytes never claim signature or admission proof."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from devnet.cbor import Raw, Tag, enc
from devnet.ntcquery import decode_all as decode_original, Tagged
from devnet.scenarios.v1context import wrapped, script_hash, FLAT_OK, FLAT_OK_V3
from oracle.plutus.context.prepare import original_case


def decode_all(raw):
    def normalize(value):
        if isinstance(value, Tagged):
            return Tag(value.tag, normalize(value.value))
        if isinstance(value, (list, tuple)):
            return type(value)(normalize(item) for item in value)
        if isinstance(value, dict):
            return {normalize(key): normalize(item) for key, item in value.items()}
        return value
    return normalize(decode_original(raw))


def prepare():
    rows = []
    for source in sorted((ROOT / 'fixtures/reference/v1-context').glob('*.json')):
        for case in json.loads(source.read_text()):
            row = original_case(case, 'original-' + case['name'], source)
            row.update(era=case['era'].capitalize(), systemStartSeconds=0, slotLengthMilliseconds=100)
            rows.append(row)
    source = ROOT / 'fixtures/reference/context-errors/submissions/conway-pv10.json'
    for case in json.loads(source.read_text()):
        row = original_case(case, 'original-context-' + case['name'], source)
        row.update(era='Conway', systemStartSeconds=0, slotLengthMilliseconds=100)
        rows.append(row)
    originals = list(rows)
    anchors = ['https://example.invalid/matrix', b'\x67' * 32]
    purposes = ['spending', 'minting', 'certifying', 'rewarding', 'voting', 'proposing']
    for pv in range(5, 12):
        era = 'Alonzo' if pv < 7 else 'Babbage' if pv < 9 else 'Conway'
        basepv = min(pv, 10)
        base = next(row for row in originals if row['name'] == f'original-{era.lower()}-pv{basepv}-v1-plain')
        for lang in range(1, (2 if pv < 7 else 3 if pv < 9 else 4)):
            # Use each language's retained network parameter list; no invented model coefficients.
            models = next(row['costModels'] for row in originals if str(lang - 1) in row['costModels'])
            code = wrapped(FLAT_OK_V3 if lang == 3 else FLAT_OK)
            policy = script_hash(lang, code)
            count = 4 if pv < 9 else 6
            for selected in [[tag] for tag in range(count)] + [list(range(count))]:
                row = deepcopy(base)
                body, witnesses, valid, aux = decode_all(bytes.fromhex(base['transaction']))
                for key in [4, 5, 9, 19, 20, 21, 22, 18]:
                    body.pop(key, None)
                for key in [3, 6, 7, 5, 4]:
                    witnesses.pop(key, None)
                witnesses[{1: 3, 2: 6, 3: 7}[lang]] = [code]
                witnesses[5] = {(tag, 0): [Tag(121, [tag, b'redeemer']), [2_000_000, 1_000_000_000]] for tag in selected}
                if pv < 9:
                    witnesses[5] = [[tag, index, value[0], value[1]] for (tag, index), value in witnesses[5].items()]
                # Deliberately nonzero start, unequal slot length and finite interval test the clock.
                body.update({8: 23, 3: 71, 14: [b'\x31' * 28, b'\x22' * 28]})
                for tag in selected:
                    if tag == 0:
                        spending = next(entry for entry in row['utxo'] if decode_all(bytes.fromhex(entry['input'])) in body[0])
                        output = decode_all(bytes.fromhex(spending['output']))
                        datum = enc(Tag(121, [42, b'datum']))
                        dh = hashlib.blake2b(datum, digest_size=32).digest()
                        if isinstance(output, list):
                            output[0] = b'\x70' + policy
                            output = output[:2] + [dh]
                        else:
                            output[0] = b'\x70' + policy
                            output[2] = [0, dh]
                        spending['output'] = enc(output).hex()
                        witnesses[4] = [Raw(datum)]
                    elif tag == 1:
                        body[9] = {policy: {b'z': 3, b'a': -2, b'': 1}}
                    elif tag == 2:
                        body[4] = [[1, [1, policy]]]  # Legacy deregistration is meaningful in every era.
                    elif tag == 3:
                        body[5] = {b'\xf0' + policy: 123456}
                    elif tag == 4:
                        body[19] = {(3, policy): {(b'\x22' * 32, 2): [1, anchors]}}
                    elif tag == 5:
                        body[20] = [[100000000000, b'\xe0' + b'\x44' * 28, [0, None, {}, policy], anchors]]
                row['name'] = f'api-{era.lower()}-pv{pv}-v{lang}-' + ('all-purposes' if len(selected) > 1 else purposes[selected[0]])
                row.update(transaction=enc([body, witnesses, valid, aux]).hex(), protocolMajor=pv,
                           costModels={str(lang - 1): models[str(lang - 1)]}, systemStartSeconds=1596059091,
                           slotLengthMilliseconds=250, language=lang, purposes=selected)
                row['provenance'] = {'kind': 'api-only-derived-transaction', 'sourceCase': base['name'],
                                     'sourceTransactionSha256': hashlib.sha256(bytes.fromhex(base['transaction'])).hexdigest(),
                                     'signaturesRevalidated': False, 'ledgerAdmissionClaim': False}
                rows.append(row)
                # Every era/language's spending datum and reference-input constraints are measured.
                if selected == [0] and pv >= 7:
                    for variant in ['inline-spending', 'reference-inline', 'reference-script']:
                        derived = deepcopy(row)
                        b, w, v, a = decode_all(bytes.fromhex(derived['transaction']))
                        if variant == 'inline-spending':
                            spent = next(e for e in derived['utxo'] if decode_all(bytes.fromhex(e['input'])) in b[0])
                            out = decode_all(bytes.fromhex(spent['output']))
                            if isinstance(out, list):
                                out = {0: out[0], 1: out[1]}
                            out[2] = [1, Tag(24, datum)]
                            spent['output'] = enc(out).hex()
                            w.pop(4)
                        else:
                            ri = [b'\x89' * 32, 4]
                            b[18] = [ri]
                            out = {0: b'\x60' + b'\x25' * 28, 1: 3456789}
                            if variant == 'reference-inline':
                                out[2] = [1, Tag(24, enc(Tag(122, [7, b'ref'])))]
                            else:
                                out[3] = Tag(24, enc([lang, code]))
                            derived['utxo'].append({'input': enc(ri).hex(), 'output': enc(out).hex()})
                        derived.update(name=row['name'] + '-' + variant, transaction=enc([b, w, v, a]).hex())
                        rows.append(derived)
    # Replay the rich anchored governance and mixed-language preparation-order controls from #176
    # with full contexts (the old collector corpus recorded only hashes on successful rows).
    source = ROOT / 'fixtures/reference/context-errors/collection.json'
    for case in json.loads(source.read_text()):
        if case['name'].startswith('api-'):
            row = {k: deepcopy(v) for k, v in case.items() if k != 'oracle'}
            row.update(name='rich-' + row['name'], era='Conway', systemStartSeconds=0, slotLengthMilliseconds=100)
            rows.append(row)
    return rows


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.write_text(json.dumps(prepare(), indent=1) + '\n')
