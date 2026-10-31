#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the pre-Conway update-value decoding oracle (#470, #436).

Below decoder version 12 the reference reads each value of a `PParamsUpdate` at the type of its
field when it decodes the body (`SparseKeyed`, `mkField`, `Core/PParams.hs:257-293`, cardano-ledger
f649f975): a `Word16`, `Word32` or `Word64` (`CompactForm Coin`, `Coin`, `EpochInterval`), a
`UnitInterval` or `NonNegativeInterval` (tag 30 over two integers, bounded over `Word64`), `Prices`,
`ExUnits` (each unit at most `maxBound :: Int64`), a `Nonce`, and the protocol version, read by
`decodeProtVer`, whose major is at most one above the era's `ProtVerHigh`.

Each era from Shelley to Babbage gets, for every update key it has except the cost models (18,
covered by the cost-model corpora), the value at the bound of its type and one over (a rational also
inside its interval and without tag 30), and, once for each type, the other forms of the item: a
wide head, a negative integer, the wrong major type, the wrong length, a zero denominator. The protocol version gets the era's largest
major and one more, a minor at and over `Word32`, and records of one and three items. The extra
entropy gets each arity. `Prices` and `ExUnits` are `RecD` records, which take a definite list only
below version 12 (`decodeCount`, `Decoding/Coders.hs:482`); Conway reads them with the same decoders,
so a Conway parameter change also sets each, definite and indefinite.

The committed corpus is `fixtures/reference/update-values/cases.jsonl`, written in two steps as for
`definite_text_cases.py`:

    python3 scripts/oracle/conway-decoding/update_value_cases.py --inputs /tmp/update-values-inputs.json
    python3 scripts/oracle/conway-decoding/update_value_cases.py --inputs /tmp/update-values-inputs.json \\
        --reference /tmp/update-values-reference.json --out fixtures/reference/update-values/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402  (the CBOR helpers)
import definite_text_cases as t  # noqa: E402  (the merge)
import pre_conway_cases as p  # noqa: E402  (the era key sets and the update body)

ERAS = ["shelley", "allegra", "mary", "alonzo", "babbage"]
# `succVersion (ProtVerHigh era)`: the largest major `decodeProtVer` takes in each era.
MAX_MAJOR = {"shelley": 3, "allegra": 4, "mary": 5, "alonzo": 7, "babbage": 9}
WORD64 = [0, 1, 5, 6, 15, 16, 17]
WORD32 = [2, 3, 7, 22]
WORD16 = [4, 8, 23, 24]
NON_NEGATIVE = [9]
UNIT = [10, 11, 12]
EX_UNITS = [20, 21]
INT64_MAX = 2**63 - 1
WORD64_MAX = 2**64 - 1
CASES = []


def ratio(n, d):
    return c.tag(30, c.array([n, d]))


def integer(v):
    """`v` in its shortest form: an unsigned or negative integer, or a bignum past 64 bits."""
    if v > WORD64_MAX:
        return c.bignum(v)
    return c.uint(v) if v >= 0 else c.nint(v)


# Fragments of the reference failure for each refusal.
WORD = {"Word64": "expected word", "Word32": "expected word32", "Word16": "expected word16"}
TAG = "Expected tag"
BOUNDED = "BoundedRatio"
ZERO = "Denominator cannot be zero"
RATIONAL_SIZE = "Size mismatch when decoding Rational"
LIST = "expected list len or indef"
RECORD_SIZE = "Size mismatch when decoding Record"
EXCESS = "Excess terms in array"
INT64 = "values must not exceed maxBound :: Int64"
MAJOR = "exceeds the maximum expected version"
VERSION = "Unsupported version value"
NONCE_SIZE = "Size mismatch when decoding Sum Nonce"
BYTES = "expected bytes"
HASH_SIZE = "Number of bytes mismatch"
INVALID_KEY = "not a valid key"


def case(era, name, fields, refused=None):
    """A body whose update sets `fields`; `refused` is the reference's rule and Scarab's error case."""
    name = f"{era} update value: {name}"
    rule, expect = refused or (None, None)
    CASES.append(
        {
            "id": c.case_id(name),
            "name": name,
            "era": era,
            "kind": "body",
            "rule": rule,
            "expect": expect,
            "bytes": p.update_body(era, fields).hex(),
        }
    )


def one(era, key, name, value, refused=None):
    case(era, f"key {key}, {name}", [(c.uint(key), value)], refused)


def words(era, keys, width, bound):
    """Every key at its bound and one over; the forms of the item once, at the first key of the width."""
    rule = WORD[width]
    present = [k for k in keys if k in p.KEYS[era]]
    for key in present:
        one(era, key, f"{width} at its bound", integer(bound))
        # 2^64 is a bignum, not an unsigned integer item
        over = "Expected" if width == "Word64" else "NumberOutOfRange"
        one(era, key, f"{width} one over its bound", integer(bound + 1), (rule, over))
    key = present[0]
    one(era, key, f"{width} 1 written with an eight-byte head", c.uint(1, 8))
    one(era, key, f"{width} negative", c.nint(-1), (rule, "Expected"))
    one(era, key, f"{width} as text", c.text("7"), (rule, "Expected"))


def rationals(era, keys, unit):
    """Every key inside, at and over its interval; the forms of the item once, at the first key."""
    top = (1, 1) if unit else (WORD64_MAX, 1)
    kind = "unit interval" if unit else "non-negative interval"
    present = [k for k in keys if k in p.KEYS[era]]
    for key in present:
        one(era, key, f"{kind} one half", ratio(c.uint(1), c.uint(2)))
        one(era, key, f"{kind} at its bound", ratio(integer(top[0]), integer(top[1])))
        if unit:
            one(era, key, f"{kind} three halves", ratio(c.uint(3), c.uint(2)), (BOUNDED, "RationalOutOfBounds"))
        one(era, key, f"{kind} with a numerator over Word64",
            ratio(integer(WORD64_MAX + 1), integer(WORD64_MAX + 2)), (BOUNDED, "RationalOutOfBounds"))
        one(era, key, f"{kind} without tag 30", c.array([c.uint(1), c.uint(2)]), (TAG, "ExpectedTag"))
    key = present[0]
    one(era, key, f"{kind} minus one half", ratio(c.nint(-1), c.uint(2)), (BOUNDED, "RationalOutOfBounds"))
    one(era, key, f"{kind} one half written with two negative integers", ratio(c.nint(-1), c.nint(-2)))
    one(era, key, f"{kind} with a zero denominator", ratio(c.uint(1), c.uint(0)), (ZERO, "ZeroDenominator"))
    one(era, key, f"{kind} of three integers", c.tag(30, c.array([c.uint(1)] * 3)),
        (RATIONAL_SIZE, "ArrayLength"))
    one(era, key, f"{kind} as an integer", c.uint(1), (TAG, "ExpectedTag"))


HALF = ratio(c.uint(1), c.uint(2))


def records(era):
    if 19 in p.KEYS[era]:
        one(era, 19, "prices", c.array([HALF, HALF]))
        one(era, 19, "prices written indefinite", c.array([HALF, HALF], indef=True), (EXCESS, "IndefiniteLength"))
        one(era, 19, "prices of one interval", c.array([HALF]), (RECORD_SIZE, "ArrayLength"))
        one(era, 19, "prices of three intervals", c.array([HALF] * 3), (RECORD_SIZE, "ArrayLength"))
        one(era, 19, "prices with an untagged interval", c.array([HALF, c.array([c.uint(1), c.uint(2)])]),
            (TAG, "ExpectedTag"))
        one(era, 19, "prices as an integer", c.uint(1), (LIST, "Expected"))
    for key in EX_UNITS:
        if key not in p.KEYS[era]:
            continue
        one(era, key, "execution units at the Int64 bound", c.array([integer(INT64_MAX)] * 2))
        one(era, key, "execution units written indefinite", c.array([c.uint(1), c.uint(2)], indef=True),
            (EXCESS, "IndefiniteLength"))
        one(era, key, "execution units with memory over the Int64 bound",
            c.array([integer(INT64_MAX + 1), c.uint(1)]), (INT64, "NumberOutOfRange"))
        one(era, key, "execution units with steps over the Int64 bound",
            c.array([c.uint(1), integer(INT64_MAX + 1)]), (INT64, "NumberOutOfRange"))
        one(era, key, "execution units with steps over Word64",
            c.array([c.uint(1), integer(WORD64_MAX + 1)]), (WORD["Word64"], "Expected"))
        one(era, key, "execution units of one item", c.array([c.uint(1)]), (RECORD_SIZE, "ArrayLength"))
        one(era, key, "execution units of three items", c.array([c.uint(1)] * 3), (RECORD_SIZE, "ArrayLength"))
        one(era, key, "execution units as an integer", c.uint(1), (LIST, "Expected"))


def protocol_version(era):
    top = MAX_MAJOR[era]
    one(era, 14, "protocol version at the era's largest major", c.array([c.uint(top), c.uint(0)]))
    one(era, 14, "protocol version one over the era's largest major", c.array([c.uint(top + 1), c.uint(0)]),
        (MAJOR, "NumberOutOfRange"))
    one(era, 14, "protocol version at major 13", c.array([c.uint(13), c.uint(0)]), (MAJOR, "NumberOutOfRange"))
    one(era, 14, "protocol version at major 14", c.array([c.uint(14), c.uint(0)]), (VERSION, "NumberOutOfRange"))
    one(era, 14, "protocol version with a major over Word32", c.array([integer(2**32), c.uint(0)]),
        (WORD["Word32"], "NumberOutOfRange"))
    one(era, 14, "protocol version with a minor at the Word32 bound",
        c.array([c.uint(top), integer(2**32 - 1)]))
    one(era, 14, "protocol version with a minor over Word32", c.array([c.uint(top), integer(2**32)]),
        (WORD["Word32"], "NumberOutOfRange"))
    one(era, 14, "protocol version written indefinite", c.array([c.uint(top), c.uint(0)], indef=True))
    one(era, 14, "protocol version of one item", c.array([c.uint(top)]), (RECORD_SIZE, "ArrayLength"))
    one(era, 14, "protocol version of three items", c.array([c.uint(top), c.uint(0), c.uint(0)]),
        (RECORD_SIZE, "ArrayLength"))
    one(era, 14, "protocol version as an integer", c.uint(top), (LIST, "Expected"))


def entropy(era):
    if 13 not in p.KEYS[era]:
        return
    hash_ = c.bstr(c.h(0x5B, 32))
    one(era, 13, "neutral nonce", c.array([c.uint(0)]))
    one(era, 13, "nonce", c.array([c.uint(1), hash_]))
    one(era, 13, "neutral nonce with a hash", c.array([c.uint(0), hash_]), (NONCE_SIZE, "TaggedArity"))
    one(era, 13, "nonce without its hash", c.array([c.uint(1)]), (BYTES, "TaggedArity"))
    one(era, 13, "nonce with a hash of 31 bytes", c.array([c.uint(1), c.bstr(c.h(0x5B, 31))]),
        (HASH_SIZE, "ByteLength"))
    one(era, 13, "nonce of tag 2", c.array([c.uint(2)]), (INVALID_KEY, "TaggedArity"))
    one(era, 13, "nonce as an integer", c.uint(0), (LIST, "Expected"))


for era in ERAS:
    words(era, WORD64, "Word64", WORD64_MAX)
    words(era, WORD32, "Word32", 2**32 - 1)
    words(era, WORD16, "Word16", 2**16 - 1)
    rationals(era, NON_NEGATIVE, unit=False)
    rationals(era, UNIT, unit=True)
    records(era)
    protocol_version(era)
    entropy(era)
    every = [(c.uint(k), p.value(k, era)) for k in p.KEYS[era]]
    case(era, "every field of the era", every)
    case(era, "a good value, then a bad one", [(c.uint(0), c.uint(44)), (c.uint(4), integer(2**16))],
         (WORD["Word16"], "NumberOutOfRange"))


def conway(name, value, key, refused=None):
    """A Conway body proposing a parameter change that sets `key` to `value`."""
    name = f"conway update value: key {key}, {name}"
    rule, expect = refused or (None, None)
    raw = c.proposals(c.proposal(c.param_change([(c.uint(key), value)])))
    CASES.append(
        {"id": c.case_id(name), "name": name, "era": "conway", "kind": "body", "rule": rule,
         "expect": expect, "bytes": raw.hex()}
    )


# `Prices` and `ExUnits` are `RecD` records in Conway too, read by the same decoders below version 12.
conway("prices", c.array([HALF, HALF]), 19)
conway("prices written indefinite", c.array([HALF, HALF], indef=True), 19, (EXCESS, "IndefiniteLength"))
for key in EX_UNITS:
    conway("execution units", c.array([c.uint(1), c.uint(2)]), key)
    conway("execution units written indefinite", c.array([c.uint(1), c.uint(2)], indef=True), key,
           (EXCESS, "IndefiniteLength"))


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
