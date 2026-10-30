#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the url-length decoding oracle (#550).

`Url` and `DnsName` are read with `textDecCBOR`, which bounds the text by its length in bytes of
UTF-8 (`textSizeN`, `lengthWord8`): 128 bytes from decoder version 9 and 64 below it
(`instance DecCBOR Url`, `ifDecoderVersionAtLeast (natVersion @9) (textDecCBOR 128) (textDecCBOR 64)`,
`libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:644-697`, cardano-ledger f649f975).

Each place is written at its era's bound (accepted) and one byte over it (refused with
"Text exceeds <n> bytes"):

- the pool metadata url of a registration, in each era from Shelley to Conway; below version 9 also a
  url of 128 bytes, which only version 9 takes, and a url of two-byte characters at the bound and
  one character over, so that the bound is of bytes, not of characters;
- the anchor url of a Conway DRep registration, of a proposal and of a vote.

The relay DNS name is the other `textDecCBOR` field; a registration's relays are kept as their
original bytes and are decoded with the body only under #436, which adds its cases.

The committed corpus is `fixtures/reference/url-length/cases.jsonl`, written in two steps as for
`definite_text_cases.py`:

    python3 scripts/oracle/conway-decoding/url_length_cases.py --inputs /tmp/url-length-inputs.json
    python3 scripts/oracle/conway-decoding/url_length_cases.py --inputs /tmp/url-length-inputs.json \\
        --reference /tmp/url-length-reference.json --out fixtures/reference/url-length/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402  (the CBOR helpers)
import definite_bytes_cases as d  # noqa: E402  (the body builder)
import definite_text_cases as t  # noqa: E402  (the certificates, anchors and the merge)

ERAS = ["shelley", "allegra", "mary", "alonzo", "babbage", "conway"]
# Scarab's error case for text over its bound.
TOO_LONG = "TextTooLong"
CASES = []


def bound(era):
    """The bound of the era's decoder version: Conway reads at 9, every earlier era below it."""
    return 128 if era == "conway" else 64


def url(size, char="a"):
    """A url of exactly `size` bytes of UTF-8, of the character `char` after an ASCII prefix."""
    prefix = "https://x/"
    width = len(char.encode())
    assert (size - len(prefix)) % width == 0
    return prefix + char * ((size - len(prefix)) // width)


def case(name, era, raw, limit=None):
    """A body case; `limit` is the bound the reference refuses it for, None for an accepted one."""
    CASES.append(
        {
            "id": c.case_id(name),
            "name": name,
            "era": era,
            "kind": "body",
            "rule": None if limit is None else f"Text exceeds {limit} bytes",
            "expect": None if limit is None else TOO_LONG,
            "bytes": raw.hex(),
        }
    )


def pool(era, text):
    return d.body(era, {4: t.certs(t.pool_cert(c.array([]), c.text(text)))})


for era in ERAS:
    n = bound(era)
    case(f"{era} body pool registration metadata url: {n} bytes", era, pool(era, url(n)))
    case(f"{era} body pool registration metadata url: {n + 1} bytes", era, pool(era, url(n + 1)), n)
    if n == 64:
        case(f"{era} body pool registration metadata url: 128 bytes", era, pool(era, url(128)), 64)
        # "é" is two bytes of UTF-8: 64 bytes is 37 characters, 66 bytes is 38
        case(f"{era} body pool registration metadata url: 64 bytes of two-byte characters", era,
             pool(era, url(64, "é")))
        case(f"{era} body pool registration metadata url: 66 bytes of two-byte characters", era,
             pool(era, url(66, "é")), 64)

for size, limit in ((128, None), (129, 128)):
    case(f"conway body drep registration anchor url: {size} bytes", "conway",
         d.body("conway", {4: t.certs(t.drep_registration(c.text(url(size))))}), limit)
    case(f"conway body proposal anchor url: {size} bytes", "conway",
         d.body("conway", {20: c.sset([t.proposal(c.text(url(size)))])}), limit)
    case(f"conway body vote anchor url: {size} bytes", "conway",
         d.body("conway", {19: t.votes(c.text(url(size)))}), limit)


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
