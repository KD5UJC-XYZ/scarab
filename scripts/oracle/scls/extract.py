# SPDX-License-Identifier: Apache-2.0
"""Extract original NES and external UTxO bytes from the pinned Conway4593 fixture."""
import argparse
import hashlib
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'devnet'))
import cborspan


def extract(fixture, destination):
    state = (fixture / 'state').read_bytes()
    expected = 'a18120e5c89808bbbcf64313866206200e4a5fc70b2f2a759acf75fc05e94bc9'
    if hashlib.sha256(state).hexdigest() != expected:
        raise ValueError('this helper requires the original Conway4593 fixture')
    (start, end), = cborspan.resolve(state, [1, 0, 6, 1, 1, 1])
    table = (fixture / 'tables').read_bytes()
    if hashlib.sha256(table).hexdigest() != '94851809f39841784369fe9458385a0f8c141d9bea9d8c55d291693a03186dc9':
        raise ValueError('this helper requires the original Conway4593 external tables')
    (at, _), = cborspan.children(table, 0)
    entries = cborspan.children(table, at)
    if len(entries) != 6:
        raise ValueError('the pinned fixture must have exactly three external UTxOs')
    destination.mkdir(parents=True, exist_ok=False)
    (destination / 'nes.cbor').write_bytes(state[start:end])
    for i in range(3):
        key = cborspan.payload(table, entries[2*i])
        value = cborspan.payload(table, entries[2*i+1])
        if len(key) != 34 or int.from_bytes(key[32:], 'big') >= 24:
            raise ValueError('unexpected fixture TxIn')
        (destination / f'utxo-{i}.key').write_bytes(key)
        (destination / f'utxo-{i}.cbor').write_bytes(b'\x82\x58\x20' + key[:32] + key[33:])
        (destination / f'utxo-{i}.mempack').write_bytes(value)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fixture', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    extract(args.fixture, args.destination)
