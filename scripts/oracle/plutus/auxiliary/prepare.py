# SPDX-License-Identifier: Apache-2.0
"""Original-byte synthetic auxiliary decoder/predicate cases; no signed admission claims."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from devnet.cbor import Raw, Tag, enc, head
from devnet.scenarios.v1context import wrapped, FLAT_OK, FLAT_OK_V3, FLAT_FAIL


def sparse(fields):
    return Tag(259, Raw(head(5, len(fields)) + b''.join(enc(k) + enc(v) for k, v in fields)))


def prepare():
    rows = []
    valid = wrapped(FLAT_OK)
    bad = b'\xff'
    native = [0, b'\x11' * 28]
    values = [
        ('metadata-empty', {}), ('metadata-nested', {7: [b'data', 'value', {1: [-1, 2]}]}),
        ('legacy-native', [{7: b'value'}, [[2, []], [4, 999]]]),
        ('tagged-empty', Tag(259, {})), ('native-not-satisfied-is-wellformed', Tag(259, {1: [[2, []]]})),
        ('metadata-duplicate-label', Raw(bytes.fromhex('a200010002'))),
        ('metadata-duplicate-nested-map', {0: Raw(bytes.fromhex('a201010102'))}),
        ('metadata-chunked-bytes64', {0: Raw(b'\x5f' + enc(b'x' * 32) + enc(b'y' * 32) + b'\xff')}),
        ('metadata-chunked-bytes65', {0: Raw(b'\x5f' + enc(b'x' * 32) + enc(b'y' * 33) + b'\xff')}),
        ('metadata-chunked-text64', {0: Raw(b'\x7f' + enc('é' * 16) + enc('é' * 16) + b'\xff')}),
        ('metadata-chunked-text66', {0: Raw(b'\x7f' + enc('é' * 16) + enc('é' * 17) + b'\xff')}),
        ('metadata-bytes64', {0: b'x' * 64}), ('metadata-bytes65', {0: b'x' * 65}),
        ('metadata-utf8-64', {0: 'é' * 32}), ('metadata-utf8-66', {0: 'é' * 33}),
        ('metadata-label-negative', {-1: 0}), ('metadata-label-text', {'label': 0}),
        ('metadata-null', {0: None}), ('metadata-bool', {0: True}),
        ('metadata-tagged-bignum', {0: Tag(2, b'\x01')}),
        ('metadata-integer-max', {0: 2**64-1}), ('metadata-integer-min', {0: -(2**64)}),
        ('tagged-metadata-not-map', Tag(259, {0: []})),
        ('tagged-metadata-invalid', Tag(259, {0: {0: b'x' * 65}})),
        ('legacy-metadata-invalid', [{0: True}, []]),
        ('native-wrong-hash-length', Tag(259, {1: [[0, b'\x11' * 27]]})),
        ('native-wrapped-tag', Tag(259, {1: [[2**32, b'\x11' * 28]]})),
        ('native-unknown-tag', Tag(259, {1: [[6, b'\x11' * 28]]})),
        ('native-invalid-slot', Tag(259, {1: [[4, -1]]})),
        ('native-field-not-array', Tag(259, {1: b'no'})),
        ('wrong-tag', Tag(258, {})), ('unknown-field', Tag(259, {99: []})),
        ('unsupported-v4-field', Tag(259, {5: []})), ('legacy-wrong-arity', [{}, [], []]),
        ('duplicate-native-fields', sparse([(1, [native]), (1, [[2, []]])])),
        ('duplicate-native-invalid-earlier', sparse([(1, [[0, b'\x11']]), (1, [native])])),
    ]
    for language in (1, 2, 3):
        field = language + 1
        code = valid
        values.extend([
            (f'v{language}-wellformed', Tag(259, {field: [code]})),
            (f'v{language}-malformed', Tag(259, {field: [bad]})),
            (f'v{language}-truncated-zero', Tag(259, {field: [b'\x00']})),
            (f'v{language}-runtime-error-is-wellformed', Tag(259, {field: [wrapped(FLAT_FAIL)]})),
            (f'v{language}-empty-list', Tag(259, {field: []})),
            (f'v{language}-script-not-bytes', Tag(259, {field: [42]})),
            (f'v{language}-field-not-list', Tag(259, {field: code})),
            (f'v{language}-duplicate-invalid-then-valid', sparse([(field, [bad]), (field, [code])])),
            (f'v{language}-duplicate-valid-then-invalid', sparse([(field, [code]), (field, [bad])])),
            (f'v{language}-duplicate-invalid-then-empty', sparse([(field, [bad]), (field, [])])),
            (f'v{language}-duplicate-valid-then-empty', sparse([(field, [code]), (field, [])])),
        ])
    values.append(('all-three-languages', Tag(259, {2: [valid], 3: [valid], 4: [wrapped(FLAT_OK_V3)]})))
    for pv in range(5, 12):
        era = 'Alonzo' if pv < 7 else 'Babbage' if pv < 9 else 'Conway'
        for name, auxiliary in values:
            rows.append({'name': f'{era.lower()}-pv{pv}-{name}', 'era': era, 'protocolMajor': pv,
                         'auxiliary': enc(auxiliary).hex(), 'kind': name,
                         'provenance': {'kind': 'synthetic-auxiliary-api-input', 'ledgerAdmissionClaim': False}})
    return rows


if __name__ == '__main__':
    import json
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.write_text(json.dumps(prepare(), indent=1) + '\n')
