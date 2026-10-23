#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes inputs.json, the cases the native instant-stake harness executes (#299).

Every case runs once with the stake held under the base address's credential and once under the
pointer address's pointer; an enterprise address controls that no entry changes. Transaction ids are
ordered 01..., 02... so that the TxIn map order is first then second, while the cases list
`consume` in the order a body could sign it (second before first) where the order matters.
"""
import json
import sys
from pathlib import Path

MODULUS = 1 << 64
PAYMENT = "01" * 28
STAKE = "02" * 28
ADDRESS = {
    "credential": "00" + PAYMENT + STAKE,
    # Pointer (slot 1, tx index 2, certificate index 3): variable-length naturals 01 02 03.
    "pointer": "40" + PAYMENT + "010203",
    "enterprise": "60" + PAYMENT,
}
FIRST = "01" * 32
SECOND = "02" * 32


def out(kind, txid, coin, index=0):
    return {"txId": txid, "index": index, "address": ADDRESS[kind], "coin": str(coin)}


def held(kind, coin):
    if coin is None:
        return [], []
    credentials = [{"keyHash": STAKE, "coin": str(coin)}] if kind == "credential" else []
    pointers = [{"slot": 1, "txIx": 2, "certIx": 3, "coin": str(coin)}] if kind == "pointer" else []
    return credentials, pointers


def case(name, kind, start, produce=(), consume=(), holder=None):
    credentials, pointers = held(holder or kind, start)
    return {
        "name": f"{kind}: {name}",
        "credentials": credentials,
        "pointers": pointers,
        "produce": list(produce),
        "consume": list(consume),
    }


def cases(kind):
    o = lambda txid, coin, index=0: out(kind, txid, coin, index)
    return [
        case("deletion underflows modulo Word64", kind, 1, consume=[o(FIRST, 3)]),
        case("addition overflow wraps to zero and removes the entry", kind, MODULUS - 1,
             produce=[o(FIRST, 1)]),
        case("addition overflow wraps past zero", kind, MODULUS - 1, produce=[o(FIRST, 3)]),
        case("deletion to exactly zero removes the entry", kind, 3, consume=[o(FIRST, 3)]),
        case("a missing entry inserts the amount for addition", kind, None, produce=[o(FIRST, 3)]),
        case("a missing entry inserts the amount for deletion", kind, None, consume=[o(FIRST, 3)]),
        case("a zero amount never creates an entry (addition)", kind, None, produce=[o(FIRST, 0)]),
        case("a zero amount never creates an entry (deletion)", kind, None, consume=[o(FIRST, 0)]),
        case("a zero amount leaves a held entry unchanged", kind, 5,
             produce=[o(FIRST, 0)], consume=[o(SECOND, 0)]),
        case("zero removal is observed by the next ordered deletion", kind, 3,
             consume=[o(FIRST, 3), o(SECOND, 2)]),
        case("deletion follows TxIn map order, not the order a body lists its inputs", kind, 3,
             consume=[o(SECOND, 3), o(FIRST, 2)]),
        case("several outputs of one transaction are all added", kind, MODULUS - 2,
             produce=[o(FIRST, 1, 0), o(FIRST, 2, 1)], consume=[o(SECOND, 1)]),
        case("zero removal then reinsertion within one update", kind, 3,
             produce=[o(FIRST, 1)], consume=[o(FIRST, 4, 1), o(SECOND, 5)]),
        case("outputs are added before consumed outputs are deleted (balanced)", kind, None,
             produce=[o(FIRST, 1)], consume=[o(SECOND, 1)]),
        case("outputs are added before consumed outputs are deleted (unbalanced)", kind, None,
             produce=[o(FIRST, 1)], consume=[o(SECOND, 2)]),
        case("the other map and an address without stake leave the entry unchanged", kind, 7,
             produce=[out("enterprise", FIRST, 3)], consume=[out("enterprise", SECOND, 3)],
             holder="credential" if kind == "pointer" else "pointer"),
    ]


def main(path):
    document = {
        "coinChecks": [str(n) for n in (-1, 0, 1, MODULUS - 1, MODULUS, MODULUS + 1)],
        "cases": cases("credential") + cases("pointer"),
    }
    Path(path).write_text(json.dumps(document, indent=1) + "\n")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "scripts/oracle/instant-stake/inputs.json")
