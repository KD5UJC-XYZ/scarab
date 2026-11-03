#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the whole-transaction envelope oracle (#688, #699).

A whole transaction is what a client or a peer submits (`LocalTxSubmission`, `TxSubmission2`); a
block holds its transactions segregated, so these decoders are reached through submission only.
- Before Alonzo it is the `RecD` record `ShelleyTx` over the body, the witness set and the auxiliary
  data or null (`eras/shelley/impl/src/Cardano/Ledger/Shelley/Tx.hs:184-192`, cardano-ledger
  f649f975): `decodeCount (RecD cn) n` reads the list head around a decoder that reads nothing
  (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Coders.hs:482`), so a non-empty
  indefinite list fails and an empty one is a detached record (#687).
- From Alonzo it is `decodeRecordNamed "AlonzoTx"` over the body, the witness set, a validity flag
  read only when the third item is a boolean, and the auxiliary data or null
  (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:532-553`): three items without the flag (the
  transaction is valid), four with it, definite or indefinite.

Each case is read with `ids` and `wire` set, so for an accepted transaction the harness reports the
transaction id it hashes and the bytes the node relays for it (its `encCBOR`, which consensus wraps
in CBOR for transaction submission). The committed corpus is
`fixtures/reference/tx-envelope/cases.jsonl`, written in two steps:

    python3 scripts/oracle/conway-decoding/tx_envelope_cases.py --inputs /tmp/tx-envelope-inputs.json
    docker run --rm --network none --read-only -v /tmp:/fixtures:ro \\
        scarab-conway-decoding:local /fixtures/tx-envelope-inputs.json > /tmp/tx-envelope-reference.json
    python3 scripts/oracle/conway-decoding/tx_envelope_cases.py --inputs /tmp/tx-envelope-inputs.json \\
        --reference /tmp/tx-envelope-reference.json --out fixtures/reference/tx-envelope/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402  (the CBOR helpers)
import definite_bytes_cases as d  # noqa: E402  (the era bodies)

ERAS = ["shelley", "allegra", "mary", "alonzo", "babbage", "conway"]
CASES = []
FALSE = b"\xf4"


def since(era, first):
    return ERAS.index(era) >= ERAS.index(first)


def case(era, name, raw):
    name = f"{era} transaction {name}"
    CASES.append({"id": c.case_id(name), "name": name, "era": era, "kind": "transaction",
                  "bytes": raw.hex(), "ids": True, "wire": True})


def items(parts, indef=False):
    return c.array(parts, indef=indef)


def head_of(count, parts):
    """A definite list head of `count` items before `parts`, whatever their number."""
    return bytes([0x80 | count]) + b"".join(parts)


WITS = c.cmap([(c.uint(0), c.array([c.vkey_witness()]))])
METADATA = c.cmap([(c.uint(674), c.text("x"))])
# the list form of auxiliary data, `[metadata, scripts]`, from Allegra
LIST_AUX = c.array([METADATA, c.array([])])
# the tagged form of auxiliary data, from Alonzo
TAGGED_AUX = c.tag(259, c.cmap([(c.uint(0), METADATA)]))
ADDRESS = c.bstr(b"\x61" + c.h(0x22, 28))
MULTIASSET = c.cmap([(c.bstr(c.h(0x88, 28)), c.cmap([(c.bstr(b"a"), c.uint(1))]))])

for era in ERAS:
    body = d.body(era)
    if not since(era, "alonzo"):
        # `ShelleyTx`, a `RecD` record of three fields
        case(era, "definite", items([body, WITS, c.NULL]))
        case(era, "definite with auxiliary data", items([body, WITS, METADATA]))
        case(era, "indefinite", items([body, WITS, c.NULL], indef=True))
        case(era, "indefinite with auxiliary data", items([body, WITS, METADATA], indef=True))
        case(era, "detached", b"\x9f\xff" + body + WITS + c.NULL)
        case(era, "with a validity flag", items([body, WITS, c.TRUE, c.NULL]))
        case(era, "two items", items([body, WITS]))
        case(era, "empty", items([]))
        if since(era, "allegra"):
            case(era, "definite with list auxiliary data", items([body, WITS, LIST_AUX]))
        continue
    # `AlonzoTx`: the flag is read when the third item is a boolean
    case(era, "with its flag", items([body, WITS, c.TRUE, c.NULL]))
    case(era, "with its flag false", items([body, WITS, FALSE, c.NULL]))
    case(era, "with its flag and auxiliary data", items([body, WITS, c.TRUE, METADATA]))
    case(era, "indefinite with its flag", items([body, WITS, c.TRUE, c.NULL], indef=True))
    case(era, "without its flag", items([body, WITS, c.NULL]))
    case(era, "without its flag with auxiliary data", items([body, WITS, METADATA]))
    case(era, "without its flag with list auxiliary data", items([body, WITS, LIST_AUX]))
    case(era, "without its flag with tagged auxiliary data", items([body, WITS, TAGGED_AUX]))
    case(era, "indefinite without its flag", items([body, WITS, c.NULL], indef=True))
    case(era, "indefinite without its flag with auxiliary data", items([body, WITS, METADATA], indef=True))
    # the third item is read as the flag; the auxiliary data is then missing
    case(era, "a flag and no auxiliary data", items([body, WITS, c.TRUE]))
    case(era, "indefinite with a flag and no auxiliary data", items([body, WITS, FALSE], indef=True))
    # the third item is not a boolean, so it is the auxiliary data and the record has three items
    case(era, "four items with null third", items([body, WITS, c.NULL, c.NULL]))
    case(era, "four items with auxiliary data third", items([body, WITS, METADATA, c.NULL]))
    case(era, "indefinite four items with null third", items([body, WITS, c.NULL, c.NULL], indef=True))
    case(era, "two items", items([body, WITS]))
    case(era, "five items", items([body, WITS, c.TRUE, c.NULL, c.NULL]))
    case(era, "empty", items([]))
    # the list head and the items disagree
    case(era, "head of four over three items", head_of(4, [body, WITS, c.NULL]))
    case(era, "head of three over four items", head_of(3, [body, WITS, c.TRUE, c.NULL]))
    # `AlonzoTx` is not a `RecD` record: its first field is read where the break is
    case(era, "detached", b"\x9f\xff" + body + WITS + c.TRUE + c.NULL)
    if era == "conway":
        # a body holding a detached record (#687), without the flag: the relayed bytes keep the
        # body as read
        value = b"\x9f\xff" + c.uint(2_000_000) + MULTIASSET
        rich = d.body(era, {1: c.array([c.array([ADDRESS, value])])})
        case(era, "without its flag with a detached record", items([rich, WITS, c.NULL]))
        case(era, "without its flag with detached auxiliary data",
             items([body, WITS, b"\x9f\xff" + METADATA + c.array([])]))


def merge(reference):
    """The corpus lines: each case with the verdict, the id and the relayed bytes the harness
    recorded for it."""
    assert len(reference) == len(CASES), "the harness answered a different number of cases"
    lines = []
    for case_, verdict in zip(CASES, reference):
        assert (verdict["name"], verdict["era"], verdict["kind"]) == (case_["name"], case_["era"], case_["kind"])
        result = verdict["result"]
        assert "unsupported" not in result, case_["name"]
        line = {
            "id": case_["id"],
            "name": case_["name"],
            "era": case_["era"],
            "kind": case_["kind"],
            "accepted": result["accepted"],
            "failure": result.get("failure"),
            "txid": result.get("id"),
            "wire": result.get("wire"),
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
