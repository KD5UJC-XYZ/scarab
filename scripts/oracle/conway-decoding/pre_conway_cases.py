#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the pre-Conway set-decoding oracle (AUD3-03/#329).

For each era from Shelley to Babbage (decoder versions 2 to 7) a body and a witness set whose sets
repeat an element, written in different forms, or carry the tag 258. The reference reads such a set
with `decodeSet`: below version 9 it collapses repeated elements by decoded value and accepts no tag.

    python3 scripts/oracle/conway-decoding/pre_conway_cases.py --out fixtures/reference/pre-conway-sets/cases.json
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402  (shares the CBOR helpers; its own cases are not written here)

ERAS = ["shelley", "allegra", "mary", "alonzo", "babbage"]
TAG_RULE = "expected list len or indef"
CASES = []


def case(name, era, kind, raw, expect=None, rule=None, size=None):
    """`size` is the number of inputs (body) or vkey witnesses (witness set) Scarab must keep."""
    CASES.append(
        {
            "id": c.case_id(name),
            "name": name,
            "era": era,
            "kind": kind,
            "rule": rule,
            "expect": expect,
            "size": size,
            "bytes": raw.hex(),
        }
    )


def body(inputs, era):
    fields = {0: inputs, 1: c.array([c.OUTPUT]), 2: c.uint(200_000)}
    if era == "shelley":
        fields[3] = c.uint(1_000)
    return c.cmap([(c.uint(k), fields[k]) for k in sorted(fields)])


def vkeys(items):
    return c.cmap([(c.uint(0), items)])


for era in ERAS:
    one = c.sset([c.txin(1)])
    case(f"{era} body: baseline", era, "body", body(one, era), size=1)
    case(f"{era} body: two distinct inputs", era, "body", body(c.sset([c.txin(1), c.txin(2)]), era), size=2)
    case(f"{era} body: indefinite length", era, "body", body(c.sset([c.txin(1), c.txin(2)], indef=True), era), size=2)
    case(f"{era} body: repeated input", era, "body", body(c.sset([c.txin(1), c.txin(1)]), era), size=1)
    case(
        f"{era} body: repeated input written with a wide index",
        era,
        "body",
        body(c.sset([c.txin(1), c.txin(1, wide_index=True)]), era),
        size=1,
    )
    case(
        f"{era} body: repeated input written with a wide array header",
        era,
        "body",
        body(c.sset([c.txin(1, wide_array=True), c.txin(1)]), era),
        size=1,
    )
    case(
        f"{era} body: repeated input among others, indefinite length",
        era,
        "body",
        body(c.sset([c.txin(1), c.txin(2), c.txin(1, wide_index=True)], indef=True), era),
        size=2,
    )
    case(
        f"{era} body: tag 258 is not read",
        era,
        "body",
        body(c.sset([c.txin(1)], tagged=True), era),
        "Expected",
        TAG_RULE,
    )
    case(f"{era} witnesses: one vkey witness", era, "witnesses", vkeys(c.array([c.vkey_witness(0)])), size=1)
    case(
        f"{era} witnesses: repeated vkey witness",
        era,
        "witnesses",
        vkeys(c.array([c.vkey_witness(0), c.vkey_witness(0)])),
        size=1,
    )
    case(
        f"{era} witnesses: repeated vkey witness written with a wide array header",
        era,
        "witnesses",
        vkeys(c.array([c.vkey_witness(0), c.array([c.bstr(c.h(0x90, 32)), c.bstr(c.h(0xA0, 64))], width=1)])),
        size=1,
    )
    case(
        f"{era} witnesses: tag 258 is not read",
        era,
        "witnesses",
        vkeys(c.sset([c.vkey_witness(0)], tagged=True)),
        "Expected",
        TAG_RULE,
    )


# --- parameter updates (key 6): the era's `PParamsUpdate` key set and repeated keys -----------
# `DecCBOR (PParamsUpdate era)` below decoder version 12 is `SparseKeyed` over the update keys of
# the era's `eraPParams` (`Core/PParams.hs:257-293`): a key outside them is `invalidKey`, a repeated
# key `duplicateKey` (`applyField`, `Coders.hs:562-570`). Shelley, Allegra and Mary have keys 0 to 16
# (`Shelley/PParams.hs`), Alonzo 0 to 14 and 16 to 24 (`Alonzo/PParams.hs`), Babbage 0 to 11, 14 and
# 16 to 24 (`Babbage/PParams.hs`), all f649f975.
UNKNOWN_KEY_RULE = "not a valid key"
DUP_KEY_RULE = "Duplicate key:"
GENESIS = c.bstr(c.h(0x5A, 28))
VERSION = {"shelley": 3, "allegra": 4, "mary": 5, "alonzo": 6, "babbage": 8}
KEYS = {
    "shelley": list(range(0, 17)),
    "allegra": list(range(0, 17)),
    "mary": list(range(0, 17)),
    "alonzo": list(range(0, 15)) + list(range(16, 25)),
    "babbage": list(range(0, 12)) + [14] + list(range(16, 25)),
}
OUTSIDE = {
    "shelley": [17, 25],
    "allegra": [17, 25],
    "mary": [18, 25],
    "alonzo": [15, 25],
    "babbage": [12, 13, 15, 25],
}


def value(key, era):
    half = c.tag(30, c.array([c.uint(1), c.uint(2)]))
    special = {
        9: c.tag(30, c.array([c.uint(3), c.uint(10)])),
        10: half,
        11: half,
        12: half,
        13: c.array([c.uint(0)]),
        14: c.array([c.uint(VERSION[era]), c.uint(0)]),
        18: c.cmap([(c.uint(0), c.array([c.uint(0)] * 166))]),
        19: c.array([half, half]),
        20: c.array([c.uint(1), c.uint(2)]),
        21: c.array([c.uint(1), c.uint(2)]),
    }
    return special.get(key, c.uint(7))


def update_body(era, fields, indef=False):
    update = c.array([c.cmap([(GENESIS, c.cmap(fields, indef=indef))]), c.uint(10)])
    b = {0: c.sset([c.txin(1)]), 1: c.array([c.OUTPUT]), 2: c.uint(200_000), 6: update}
    if era == "shelley":
        b[3] = c.uint(1_000)
    return c.cmap([(c.uint(k), b[k]) for k in sorted(b)])


for era in ERAS:
    every = [(c.uint(k), value(k, era)) for k in KEYS[era]]
    case(f"{era} update: every field of the era", era, "body", update_body(era, every), size=1)
    case(f"{era} update: empty", era, "body", update_body(era, []), size=1)
    case(f"{era} update: indefinite map", era, "body", update_body(era, every[:2], indef=True), size=1)
    for key in OUTSIDE[era]:
        case(
            f"{era} update: key {key} is not a field of the era",
            era,
            "body",
            update_body(era, [(c.uint(0), c.uint(44)), (c.uint(key), value(key, era))]),
            "UnknownKey",
            UNKNOWN_KEY_RULE,
        )
    case(
        f"{era} update: key 2^32 is unknown",
        era,
        "body",
        update_body(era, [(c.uint(2**32), c.uint(44))]),
        "UnknownKey",
        UNKNOWN_KEY_RULE,
    )
    case(
        f"{era} update: repeated key, same value",
        era,
        "body",
        update_body(era, [(c.uint(0), c.uint(44)), (c.uint(0), c.uint(44))]),
        "DuplicateKey",
        DUP_KEY_RULE,
    )
    case(
        f"{era} update: repeated key, other value, written wide",
        era,
        "body",
        update_body(era, [(c.uint(16), c.uint(1)), (c.uint(16, 8), c.uint(2))]),
        "DuplicateKey",
        DUP_KEY_RULE,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    names = [x["name"] for x in CASES]
    assert len(names) == len(set(names)), "case names must be unique"
    ids = [x["id"] for x in CASES]
    assert len(ids) == len(set(ids)), "case ids must be unique"
    with open(args.out, "w") as f:
        json.dump(CASES, f, indent=1)
        f.write("\n")


if __name__ == "__main__":
    main()
