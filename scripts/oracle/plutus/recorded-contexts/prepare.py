# SPDX-License-Identifier: Apache-2.0
"""Inputs for the unchanged context-matrix observer: every recorded Preview and preprod Plutus
transaction at its own era, protocol and network clock, and API-only Conway transactions with
several voters of different kinds. Derived bytes never claim signature or admission proof."""
from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))
from devnet.cbor import Raw, Tag, enc, head  # noqa: E402
from devnet.scenarios.v1context import wrapped, script_hash, FLAT_OK_V3  # noqa: E402
from oracle.plutus.context.prepare import original_case  # noqa: E402

_spec = importlib.util.spec_from_file_location('context_matrix_prepare', HERE.parent / 'context-matrix' / 'prepare.py')
_matrix = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_matrix)
decode_all = _matrix.decode_all

# Slot to POSIX time as each network's hard-fork history gives it for Shelley-based slots, the only
# slots a Plutus validity interval names: Preview forks to Shelley at epoch 0 with 1 s slots from
# 2022-10-25T00:00:00Z (fixtures/preview/genesis); preprod has four Byron epochs of 21600 slots of
# 20 s from 1654041600, so Shelley slot s is at 1655769600 + (s - 86400) s (fixtures/preprod/genesis).
NETWORKS = {
    'preview': (ROOT / 'fixtures/preview/plutus', 1666656000),
    'preprod': (ROOT / 'fixtures/preprod/plutus', 1655683200),
}
SLOT_MILLISECONDS = 1000
VOTE_BASE = 'slot117938233-a1b750a9'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def era(major):
    return 'Alonzo' if major < 7 else 'Babbage' if major < 9 else 'Conway'


def recorded(network):
    directory, start = NETWORKS[network]
    rows = []
    for path in sorted(directory.glob('*.json')):
        case = json.loads(path.read_text())
        # The node's side reads these samples; the observer reads the network's clock line.
        for slot, millis in case.get('clock', {}).items():
            require(int(millis) == (start + int(slot)) * SLOT_MILLISECONDS,
                    f'{path.name}: clock sample of slot {slot} is off the {network} clock')
        row = original_case(case, f'{network}/{case["name"]}', path)
        # Some recorded files write the protocol version as a string; the observer reads a number.
        major = int(case['protocolMajor'])
        row.update(protocolMajor=major, era=era(major), systemStartSeconds=start,
                   slotLengthMilliseconds=SLOT_MILLISECONDS)
        row['provenance'] = {'kind': 'recorded-chain-transaction', 'network': network,
                             'sourcePath': str(path.relative_to(ROOT)),
                             'sourceFileSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                             'transactionSha256': hashlib.sha256(bytes.fromhex(row['transaction'])).hexdigest(),
                             'signaturesRevalidated': False, 'ledgerAdmissionClaim': False}
        rows.append(row)
    require(rows, f'no recorded {network} transactions')
    return rows


def raw_map(pairs):
    """A CBOR map written in exactly the given key order (`enc` would sort it canonically)."""
    return Raw(head(5, len(pairs)) + b''.join(enc(key) + enc(value) for key, value in pairs))


def voters():
    """The recorded vote of a script committee member, beside four more voters written in the
    reverse of `Ord Voter`: a key committee member, a script DRep voting on two actions, a key
    DRep and a pool. Variants place the voting redeemers at the indices of `Ord Voter`, of the
    transaction's own order, of canonical CBOR key order, and at every index."""
    path = NETWORKS['preview'][0] / f'{VOTE_BASE}.json'
    base = original_case(json.loads(path.read_text()), VOTE_BASE, path)
    body, witnesses, valid, aux = decode_all(bytes.fromhex(base['transaction']))
    require(len(body[19]) == 1, 'the base transaction has one voter')
    ((committee, committee_votes),) = body[19].items()
    require(committee[0] == 1, 'the base voter is a script committee member')
    action = next(iter(committee_votes))
    drep_script = wrapped(FLAT_OK_V3)
    anchor = ['https://example.invalid/vote', b'\x77' * 32]
    # Ord Voter: CommitteeVoter < DRepVoter < StakePoolVoter, and ScriptHashObj < KeyHashObj.
    ordered = [
        (committee, list(committee_votes.items())),
        ((0, b'\x44' * 28), [(action, [0, None])]),
        ((3, script_hash(3, drep_script)), [(action, [2, anchor]), ((b'\x22' * 32, 1), [1, None])]),
        ((2, b'\x55' * 28), [(action, [1, None])]),
        ((4, b'\x66' * 28), [(action, [0, None])]),
    ]
    written = [(list(voter), raw_map(sorted(votes, reverse=True))) for voter, votes in reversed(ordered)]
    body[19] = raw_map(written)
    scripts = witnesses[7].value if isinstance(witnesses[7], Tag) else witnesses[7]
    scripts.append(drep_script)
    spend = witnesses[5][(0, 0)]
    recorded_vote = witnesses[5][(4, 0)]
    units = [200_000, 100_000_000]
    drep = [Tag(121, [2, b'drep']), units]
    variants = {
        'ord-indices': {(4, 0): recorded_vote, (4, 2): drep},
        'transaction-order-indices': {(4, 4): recorded_vote, (4, 2): drep},
        'canonical-order-indices': {(4, 1): recorded_vote, (4, 3): drep},
        'every-index': {(4, index): [Tag(121, [index, b'voter']), units] for index in range(5)},
    }
    rows = []
    for name, votes in variants.items():
        w = deepcopy(witnesses)
        w[5] = {(0, 0): spend, **votes}
        row = deepcopy(base)
        row.update(name=f'voters/{name}', transaction=enc([body, w, valid, aux]).hex(),
                   protocolMajor=int(base['protocolMajor']), era='Conway',
                   systemStartSeconds=NETWORKS['preview'][1], slotLengthMilliseconds=SLOT_MILLISECONDS)
        row['provenance'] = {'kind': 'api-only-derived-transaction', 'sourceCase': f'preview/{VOTE_BASE}',
                             'sourceTransactionSha256': hashlib.sha256(bytes.fromhex(base['transaction'])).hexdigest(),
                             'signaturesRevalidated': False, 'ledgerAdmissionClaim': False}
        rows.append(row)
    return rows


def prepare():
    rows = recorded('preview') + recorded('preprod') + voters()
    require(len({row['name'] for row in rows}) == len(rows), 'duplicate case name')
    return rows


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.write_text(json.dumps(prepare(), indent=1) + '\n')
