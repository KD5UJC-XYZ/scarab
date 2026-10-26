#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the era-forms decoding oracle (#379).

Two things differ by era in the transaction body decoders of cardano-ledger f649f975, and this
corpus puts each to the unchanged released decoders at the era's decoder version (Shelley 2, Allegra
3, Mary 4, Alonzo 5, Babbage 7, Conway 9):

- the forms of a transaction output: `ShelleyTxOut` (Shelley, Allegra, Mary) is the two-element
  array alone (`decodeRecordNamed "ShelleyTxOut" (const 2)`, `Shelley/TxOut.hs`); `AlonzoTxOut`
  adds the three-element array with a datum hash (`Alonzo/TxOut.hs`); `BabbageTxOut` (Babbage,
  Conway) adds the map (`decodeBabbageTxOut`, `Babbage/TxOut.hs`);
- the keys of the body: each era's `bodyFields` (`Shelley/TxBody.hs` `boxBody`, `Allegra/TxBody.hs`
  `bodyFields` for Allegra and Mary, then Alonzo, Babbage and Conway) names the keys it knows and
  `invalidField` refuses every other, and each names the keys that must be present.

Every key from 3 to 24 is written with a value that some era accepts, so the refusal of a key is for
the key and not for its value; the three required keys are left out in turn; and a key that does not
fit 32 bits is written beside a known key it would collide with if it were truncated.

    python3 scripts/oracle/conway-decoding/era_forms_cases.py --inputs /tmp/era-forms-inputs.json
    python3 scripts/oracle/conway-decoding/era_forms_cases.py --inputs /tmp/era-forms-inputs.json \\
        --reference /tmp/era-forms-reference.json --out fixtures/reference/era-forms/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402
import definite_bytes_cases as d  # noqa: E402  (its helpers only; its own cases are not written here)

ERAS = d.ERAS
since = d.since
CASES = []
# The Scarab error cases a refusal must be.
FORM = "OutputForm"
KEY = "UnknownFields"
MISSING = "MissingField"
DUPLICATE = "DuplicateKey"


def case(name, era, raw, expect=None, rule=None):
    CASES.append(
        {
            "id": c.case_id(name),
            "name": name,
            "era": era,
            "kind": "body",
            "rule": rule,
            "expect": expect,
            "bytes": raw.hex(),
        }
    )


def body(era, extra=None, drop=()):
    fields = {0: c.sset([c.txin(1)]), 1: c.array([c.OUTPUT]), 2: c.uint(200_000)}
    if era == "shelley":
        fields[3] = c.uint(1_000)
    fields.update(extra or {})
    for k in drop:
        fields.pop(k, None)
    return c.cmap([(c.uint(k), fields[k]) for k in sorted(fields)])


ADDRESS = c.bstr(d.ADDRESS)
COIN = c.uint(2_000_000)
HASH = c.bstr(c.h(0x58, 32))

# A value that some era accepts for the key.
VALUES = {
    3: c.uint(1_000),
    4: c.array([c.STAKE_REGISTRATION]),
    5: c.cmap([(c.bstr(d.ACCOUNT), c.uint(5))]),
    6: d.update(d.definite, ""),
    7: c.bstr(c.h(0x55, 32)),
    8: c.uint(5),
    9: d.multiasset(d.definite, ""),
    11: c.bstr(c.h(0x56, 32)),
    13: c.sset([c.txin(2)]),
    14: c.sset([c.bstr(c.h(0x57, 28))]),
    15: c.uint(1),
    16: c.OUTPUT,
    17: c.uint(1_000),
    18: c.sset([c.txin(3)]),
    19: d.votes(d.definite, ""),
    20: c.array([d.proposal(d.definite, "")]),
    21: c.uint(1_000),
    22: c.uint(1_000),
}
OUT_OF_RANGE = (23, 24, 100)

OUTPUTS = {
    "two elements": c.array([ADDRESS, COIN]),
    "two elements, indefinite": c.array([ADDRESS, COIN], indef=True),
    "three elements": c.array([ADDRESS, COIN, HASH]),
    "three elements, indefinite": c.array([ADDRESS, COIN, HASH], indef=True),
    "four elements": c.array([ADDRESS, COIN, HASH, HASH]),
    "one element": c.array([ADDRESS]),
    "map": c.cmap([(c.uint(0), ADDRESS), (c.uint(1), COIN)]),
    "map, indefinite": c.cmap([(c.uint(0), ADDRESS), (c.uint(1), COIN)], indef=True),
    "an integer": c.uint(1),
}

PAIRS = ("two elements", "two elements, indefinite")
HASHES = ("three elements", "three elements, indefinite")
MAPS = ("map", "map, indefinite")


def output_verdict(era, form):
    """(expect, rule) of an output in `form` in `era`: both None where the reference accepts it."""
    if form in PAIRS or (form in HASHES and since(era, "alonzo")) or (form in MAPS and since(era, "babbage")):
        return None, None
    if form in ("four elements", "one element") and since(era, "alonzo"):
        return FORM, "wrong number of terms in txout" if era == "alonzo" else "Wrong number of terms in TxOut"
    if form in ("three elements, indefinite",):
        return FORM, "Excess terms in array"
    if form in ("three elements", "four elements", "one element"):
        return FORM, "Size mismatch when decoding Record"
    return FORM, "expected list len or indef"


# The keys of the body in each era: `boxBody` (Shelley), `bodyFields` of Allegra (and Mary) and of
# the later eras. Allegra reads key 9 as `()`, which is `null`.
KEYS = {
    "shelley": set(range(3, 8)),
    "allegra": set(range(3, 10)),
    "mary": set(range(3, 10)),
    "alonzo": set(range(3, 10)) | {11, 13, 14, 15},
    "babbage": set(range(3, 10)) | {11, 13, 14, 15, 16, 17, 18},
    "conway": (set(range(3, 10)) - {6}) | {11, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22},
}


def key_verdict(era, k):
    if k in KEYS[era] and not (k == 9 and era == "allegra"):
        return None, None
    if k == 9 and era == "allegra":
        return "Expected", "expected null"
    return KEY, "not a valid key"


for era in ERAS:
    for form, out in OUTPUTS.items():
        case(f"{era} output: {form}", era, body(era, {1: c.array([out])}), *output_verdict(era, form))
    if since(era, "babbage"):
        for form in ("two elements", "three elements", "four elements", "map"):
            case(f"{era} collateral return: {form}", era, body(era, {16: OUTPUTS[form]}), *output_verdict(era, form))

    for k in sorted(VALUES):
        case(f"{era} body key {k}", era, body(era, {k: VALUES[k]}), *key_verdict(era, k))
    for k in OUT_OF_RANGE:
        case(f"{era} body key {k}", era, body(era, {k: c.uint(0)}), KEY, "not a valid key")
    # Allegra reads key 9 as `()`, which is `null`; from Mary on it is a multi-asset map.
    null9 = (None, None) if era == "allegra" else (KEY, "not a valid key") if era == "shelley" else ("Expected", "expected map len or indef")
    case(f"{era} body key 9: null", era, body(era, {9: c.NULL}), *null9)

    for k in (0, 1, 2) + ((3,) if era == "shelley" else ()):
        case(f"{era} body without key {k}", era, body(era, drop=(k,)), MISSING, "not decoded.")
    twice = [(0, c.sset([c.txin(1)])), (1, c.array([c.OUTPUT])), (2, c.uint(1)), (2, c.uint(2))]
    twice += [(3, c.uint(1))] if era == "shelley" else []
    case(f"{era} body with key 2 twice", era, c.cmap([(c.uint(k), v) for k, v in twice]), DUPLICATE, "Duplicate key:")
    # Keys beyond 32 bits: 2^32 + 3 is the key 3 (a time to live) if truncated to 32 bits.
    case(f"{era} body key 4294967299", era, body(era, {2**32 + 3: c.uint(1_000)}), "UnknownKey", "not a valid key")
    case(f"{era} body key 18446744073709551615", era, body(era, {2**64 - 1: c.uint(0)}), "UnknownKey", "not a valid key")


def merge(reference):
    """The corpus lines: each case with the verdict the harness recorded for it (absent fields are null)."""
    assert len(reference) == len(CASES), "the harness answered a different number of cases"
    lines = []
    for case_, verdict in zip(CASES, reference):
        assert (verdict["name"], verdict["era"], verdict["kind"]) == (case_["name"], case_["era"], case_["kind"])
        result = verdict["result"]
        line = {
            "id": case_["id"],
            "name": case_["name"],
            "era": case_["era"],
            "kind": case_["kind"],
            "expect": case_["expect"],
            "rule": case_["rule"],
            "accepted": result["accepted"],
            "failure": result.get("failure"),
            "bytes": case_["bytes"],
        }
        lines.append(json.dumps({k: v for k, v in line.items() if v is not None}, separators=(",", ":")))
    return lines


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", help="write the array of original encodings the harness reads")
    parser.add_argument("--reference", help="the harness output for those inputs, to merge")
    parser.add_argument("--out", help="write the corpus lines (needs --reference)")
    args = parser.parse_args()
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
            lines = merge(json.load(f))
        with open(args.out, "w") as f:
            f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
