#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the MIR amount decoding oracle (#707).

A move-instantaneous-rewards certificate that pays stake credentials holds a map from credential to
`DeltaCoin` (`StakeAddressesMIR`, `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxCert.hs:341-353`,
cardano-ledger f649f975). `DeltaCoin` is a newtype over `Integer` and derives its `DecCBOR`
(`libs/cardano-ledger-core/src/Cardano/Ledger/Coin.hs:115-119`), so the amount is read by cborg's
`decodeInteger`: an integer item of any head width, or a bignum (the one-byte tag heads `0xc2` and
`0xc3` over a definite byte string).

Each amount is written in a body's MIR certificate in every era that has one (Shelley to Babbage):
unsigned and negative integers (also with an eight-byte head), bignums of each sign within and beyond
64 bits and with leading zeros, and the forms cborg does not read as an integer (a widened tag
head, a bignum over a chunked string, text).

The committed corpus is `fixtures/reference/mir-amounts/cases.jsonl`, written in two steps as for
`definite_text_cases.py`:

    python3 scripts/oracle/conway-decoding/mir_amount_cases.py --inputs /tmp/mir-amounts-inputs.json
    python3 scripts/oracle/conway-decoding/mir_amount_cases.py --inputs /tmp/mir-amounts-inputs.json \\
        --reference /tmp/mir-amounts-reference.json --out fixtures/reference/mir-amounts/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402  (the CBOR helpers)
import definite_bytes_cases as d  # noqa: E402  (the era bodies)
import definite_text_cases as t  # noqa: E402  (the merge)

ERAS = ["shelley", "allegra", "mary", "alonzo", "babbage"]
CASES = []
CREDENTIAL = c.array([c.uint(0), c.bstr(c.KEY_HASH)])

# (name, encoding, refused): `refused` is the reference's rule and Scarab's error case.
AMOUNTS = [
    ("unsigned", c.uint(5), None),
    ("unsigned, eight-byte head", c.uint(5, width=8), None),
    ("negative", b"\x24", None),
    ("negative, eight-byte head", b"\x3b" + (4).to_bytes(8, "big"), None),
    ("bignum 1", b"\xc2" + c.bstr(b"\x01"), None),
    ("bignum 2^64", b"\xc2" + c.bstr(b"\x01" + bytes(8)), None),
    ("bignum with leading zeros", b"\xc2" + c.bstr(b"\x00\x00\x05"), None),
    ("bignum, empty", b"\xc2" + c.bstr(b""), None),
    ("negative bignum -2", b"\xc3" + c.bstr(b"\x01"), None),
    ("negative bignum -2^64 - 1", b"\xc3" + c.bstr(b"\x01" + bytes(8)), None),
    ("bignum with a widened tag head", b"\xd8\x02" + c.bstr(b"\x01"), ("expected integer", "Expected")),
    ("bignum over a chunked string", b"\xc2\x5f" + c.bstr(b"\x01") + b"\xff", ("expected integer", "Expected")),
    ("text", c.text("5"), ("expected integer", "Expected")),
]


def case(era, name, raw, refused=None):
    name = f"{era} MIR amount: {name}"
    rule, expect = refused or (None, None)
    CASES.append(
        {"id": c.case_id(name), "name": name, "era": era, "kind": "body", "rule": rule, "expect": expect,
         "bytes": raw.hex()}
    )


for era in ERAS:
    for name, amount, refused in AMOUNTS:
        mir = c.array([c.uint(6), c.array([c.uint(0), c.cmap([(CREDENTIAL, amount)])])])
        case(era, name, d.body(era, {4: c.array([mir])}), refused)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", help="write the array of original encodings the harness reads")
    parser.add_argument("--reference", help="the harness output for those inputs, to merge")
    parser.add_argument("--out", help="write the corpus lines (needs --reference)")
    args = parser.parse_args()
    t.CASES[:] = CASES
    names = [x["name"] for x in CASES]
    assert len(names) == len(set(names)), "case names must be unique"
    ids = [x["id"] for x in CASES]
    assert len(ids) == len(set(ids)), "case ids must be unique"
    if args.inputs:
        with open(args.inputs, "w") as f:
            json.dump(CASES, f, indent=1)
            f.write("\n")
    if args.out:
        if not args.reference:
            parser.error("--out needs --reference")
        with open(args.reference) as f:
            lines = t.merge(json.load(f))
        with open(args.out, "w") as f:
            f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
