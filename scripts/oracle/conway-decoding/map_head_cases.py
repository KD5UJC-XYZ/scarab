#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the map-head decoding oracle (#701).

Two decoders of a block choose between a map form and a list form with cborg's `peekTokenType`,
and send only `TypeMapLen` and `TypeMapLenIndef` to the map form (cardano-ledger f649f975):

- an output from Babbage, `decodeBabbageTxOut` (`eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:549-553`);
- the redeemers from decoder version 9 (Conway), `instance DecCBOR (Annotator (RedeemersRaw era))`
  (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:548-559`).

A map whose length is written in eight bytes (head `0xbb`) is `TypeMapLen64`, so it is read as a
list and refused; the narrower heads (`0xa0`-`0xb7`, `0xb8`, `0xb9`, `0xba`) are `TypeMapLen`. Each
place is written with each width of head. The decoders that peek and list the eight-byte tokens too
are written with the eight-byte head as controls: the multi-asset map of a `MaryValue`, a metadatum
map and the metadata of auxiliary data, and the list forms (`0x9b`) of an output and of redeemers.

The committed corpus is `fixtures/reference/map-heads/cases.jsonl`, written in two steps as for
`definite_text_cases.py`:

    python3 scripts/oracle/conway-decoding/map_head_cases.py --inputs /tmp/map-heads-inputs.json
    python3 scripts/oracle/conway-decoding/map_head_cases.py --inputs /tmp/map-heads-inputs.json \\
        --reference /tmp/map-heads-reference.json --out fixtures/reference/map-heads/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402  (the CBOR helpers)
import definite_bytes_cases as d  # noqa: E402  (the era bodies and witness sets)
import definite_text_cases as t  # noqa: E402  (the merge)

CASES = []
# The widths of a head's argument: the item itself (None), then one, two, four and eight bytes.
WIDTHS = {"in the head": None, "one-byte length": 1, "two-byte length": 2, "four-byte length": 4,
          "eight-byte length": 8}
LIST = "expected list len or indef"
ADDRESS = c.bstr(d.ADDRESS)
COIN = c.uint(2_000_000)
ASSETS = [(c.bstr(c.h(0x88, 28)), c.cmap([(c.bstr(b"a"), c.uint(1))]))]


def case(era, kind, name, raw, refused=None):
    """`refused` is the reference's rule and Scarab's error case."""
    name = f"{era} {kind} {name}"
    rule, expect = refused or (None, None)
    CASES.append(
        {"id": c.case_id(name), "name": name, "era": era, "kind": kind, "rule": rule, "expect": expect,
         "bytes": raw.hex()}
    )


def output_map(width):
    return c.cmap([(c.uint(0), ADDRESS), (c.uint(1), COIN)], width=width)


def transaction(era, aux):
    return c.array([d.body(era), c.cmap([]), c.TRUE, aux])


def redeemer_map(width):
    pair = (c.array([c.uint(0), c.uint(0)]), c.array([c.uint(0), c.array([c.uint(1), c.uint(2)])]))
    return d.wits(5, c.cmap([pair], width=width))


# --- the map form of an output, and of a collateral return, from Babbage ------------------------
for era in ("alonzo", "babbage", "conway"):
    for label, width in WIDTHS.items():
        if era == "alonzo" and width not in (None, 8):
            continue
        refused = (LIST, "OutputForm") if width == 8 or era == "alonzo" else None
        case(era, "body", f"output map, {label}", d.body(era, {1: c.array([output_map(width)])}), refused)
        if era != "alonzo":
            case(era, "body", f"collateral return map, {label}", d.body(era, {16: output_map(width)}), refused)
    # The list form peeks `TypeListLen64` and reads it (`decodeListLenOrIndef`).
    case(era, "body", "output list, eight-byte length", d.body(era, {1: c.array([c.array([ADDRESS, COIN], width=8)])}))

# --- the redeemers: the map form from Conway, the list form before it --------------------------
for era in ("alonzo", "babbage", "conway"):
    for label, width in WIDTHS.items():
        if era != "conway" and width not in (None, 8):
            continue
        refused = None if era == "conway" and width != 8 else (LIST, "Expected")
        case(era, "witnesses", f"redeemer map, {label}", redeemer_map(width), refused)
    redeemers = c.array([c.array([c.uint(0), c.uint(0), c.uint(0), c.array([c.uint(1), c.uint(2)])])], width=8)
    case(era, "witnesses", "redeemer list, eight-byte length", d.wits(5, redeemers))

# --- decoders that peek and take the eight-byte tokens: controls --------------------------------
for era in ("mary", "alonzo", "babbage", "conway"):
    value = c.array([COIN, c.cmap(ASSETS, width=8)])
    case(era, "body", "multi-asset map, eight-byte length", d.body(era, {1: c.array([c.array([ADDRESS, value])])}))
for era in ("allegra", "mary", "alonzo", "babbage", "conway"):
    flagged = (lambda aux: transaction(era, aux)) if era in ("alonzo", "babbage", "conway") else (
        lambda aux: c.array([d.body(era), c.cmap([]), aux]))
    case(era, "transaction", "metadata map, eight-byte length",
         flagged(c.cmap([(c.uint(674), c.uint(1))], width=8)))
    case(era, "transaction", "metadatum map, eight-byte length",
         flagged(c.cmap([(c.uint(674), c.cmap([(c.uint(1), c.uint(2))], width=8))])))


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
