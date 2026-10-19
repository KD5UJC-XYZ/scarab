# SPDX-License-Identifier: Apache-2.0
"""Derive multi-script API probes; retained source signatures never claim admission of mutations."""
import argparse
from copy import deepcopy
import hashlib
import itertools
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from devnet.cbor import enc
from devnet.ntcquery import decode_all
from devnet.scenarios.v1context import script_hash


def natural(value):
    groups = []
    while value >= 128:
        groups.append(format((value & 127) | 128, '08b'))
        value >>= 7
    return ''.join(groups) + format(value, '08b')


def script(label, fails, language):
    """UPLC 1.0.0: lambda redeemer/context; trace a distinct label, then unit or error."""
    bits = '000000010000000000000000' + '0010' * (1 if language == 3 else 2)
    # Force (trace @unit label (delay unit/error)); use delayed branch so failure follows trace.
    bits += '0101' + '0011' + '0011' + '0101' + '0111' + format(28, '07b')
    bits += '0100' + '1' + format(2, '04b') + '0'  # string constant
    bits += '0' * ((7 - len(bits)) % 8) + '1'
    raw = label.encode()
    bits += format(len(raw), '08b') + ''.join(format(x, '08b') for x in raw) + '00000000'
    bits += '0001' + ('0110' if fails else '0100100110')
    bits += '0' * ((7 - len(bits)) % 8) + '1'
    return enc(bytes(int(bits[i:i+8], 2) for i in range(0, len(bits), 8)))


def prepare():
    source_path = ROOT / 'fixtures/reference/context-errors/collection.json'
    originals = json.loads(source_path.read_text())
    original = next(row for row in originals if row['name'] == 'conway-pv10-v1-plain')
    base = {key: value for key, value in original.items() if key != 'oracle'}
    rows = []
    # Retain a genuine, byte-identical signed admission as a distinct control.
    rows.append(dict(name='original-signed-v1-control', transaction=base['transaction'],
                     utxo=base['utxo'], costModels=base['costModels'], protocolMajor=10,
                     provenance=dict(kind='original-signed-admitted-transaction', sourceFile=str(source_path.relative_to(ROOT)),
                                     sourceCase=original['name'], ledgerAdmissionClaim=True)))
    for language, pattern in itertools.product((1, 2, 3), itertools.product((False, True), repeat=3)):
        row = deepcopy(base)
        body, witnesses, valid, auxiliary = decode_all(bytes.fromhex(row['transaction']))
        for nonce in itertools.count():
            labels = [f'script-{i}-{nonce}' for i in range(3)]
            scripts = [script(label, failed, language) for label, failed in zip(labels, pattern)]
            ordered = sorted((script_hash(language, code), code, i) for i, code in enumerate(scripts))
            if tuple(pattern[index] for _, _, index in reversed(ordered)) == pattern:
                break
        body[9] = {policy: {b't': 1} for policy, _, _ in ordered}
        witnesses[{1: 3, 2: 6, 3: 7}[language]] = scripts
        witnesses[5] = {(1, i): [0, [200_000, 100_000_000]] for i in range(3)}

        if language != 1: witnesses.pop(3, None)
        row['name'] = f'api-v{language}-' + ''.join('fail' if failed else 'pass' for failed in pattern)
        row['transaction'] = enc([body, witnesses, valid, auxiliary]).hex()
        row['costModels'] = {k: v for original in originals for k, v in original['costModels'].items()}
        row['expectedExecutionLabels'] = [labels[index] for _, _, index in reversed(ordered)]
        row['language'] = language
        row['expectedFailures'] = [labels[index] for _, _, index in reversed(ordered) if pattern[index]]
        row['provenance'] = dict(kind='api-only-derived-transaction', sourceCase=original['name'],
                                 sourceTransactionSha256=hashlib.sha256(bytes.fromhex(base['transaction'])).hexdigest(),
                                 signaturesRevalidated=False, ledgerAdmissionClaim=False)
        rows.append(row)
    return rows


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.write_text(json.dumps(prepare(), indent=1) + '\n')
