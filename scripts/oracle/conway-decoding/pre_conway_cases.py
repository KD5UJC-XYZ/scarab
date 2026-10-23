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
