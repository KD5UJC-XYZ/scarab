#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the bootstrap chain code oracle.

A bootstrap witness is `[vkey, signature, chain code, attributes]` (`DecCBOR BootstrapWitness`,
`libs/cardano-ledger-core/src/Cardano/Ledger/Keys/Bootstrap.hs:102-106`, cardano-ledger f649f975).
The reference reads the chain code as a byte array and checks its length only from decoder version 12
(`instance DecCBOR ChainCode`, `Bootstrap.hs:72-78`): below 12 a chain code of any length is read. A
block and a submitted transaction of an era are read at the era's `eraProtVerLow` (`eraDecoder`,
`libs/cardano-ledger-core/src/Cardano/Ledger/Core/Era.hs:259-260`), from 2 for Shelley to 9 for Conway,
whatever the protocol version in force. `bootstrapWitKeyHash` (`Bootstrap.hs:112-146`) hashes the
chain code as it is, after a fixed prefix.

The cases, each with chain codes of 0, 31, 32, 33 and 64 bytes:

- in each era from Shelley to Conway, a witness set holding one bootstrap witness, a whole
  transaction with a vkey witness and that bootstrap witness, and a block body (the parts of a block
  after its header) holding that transaction;
- in Conway, a witness set holding two bootstrap witnesses that differ only in their chain codes;
- one bootstrap witness read alone (kind "bootstrap") at each decoder version from 2 to 12, which
  shows where the length check begins.

The keys, signatures and attributes are fixed filler bytes: no signature verifies and no ledger rule
runs. The harness reports, for each accepted case, the `bootstrapWitKeyHash` of each witness.

The committed corpus is one file, `fixtures/reference/bootstrap-chain-codes/cases.jsonl`, a line per
case with its encoding, what it varies and the verdict of the harness, written in two steps:

    python3 scripts/oracle/conway-decoding/bootstrap_chain_code_cases.py --inputs /tmp/bootstrap-chain-codes-inputs.json
    docker run --rm --network none --read-only -v /tmp:/fixtures:ro \\
        scarab-conway-decoding:local /fixtures/bootstrap-chain-codes-inputs.json > /tmp/bootstrap-chain-codes-reference.json
    python3 scripts/oracle/conway-decoding/bootstrap_chain_code_cases.py --inputs /tmp/bootstrap-chain-codes-inputs.json \\
        --reference /tmp/bootstrap-chain-codes-reference.json --out fixtures/reference/bootstrap-chain-codes/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402  (the CBOR helpers)
import definite_bytes_cases as d  # noqa: E402  (the era bodies)

ERAS = ["shelley", "allegra", "mary", "alonzo", "babbage", "conway"]
# `eraProtVerLow` of each era (`Cardano/Ledger/Internal/Definition/Era.hs`), and the eras read at a version
VERSION = {"shelley": 2, "allegra": 3, "mary": 4, "alonzo": 5, "babbage": 7, "conway": 9}
ERA_AT = {v: e for e, v in VERSION.items()} | {12: "dijkstra"}
LENGTHS = [0, 31, 32, 33, 64]
SIZE = 32
# `instance DecCBOR ChainCode`'s failure from version 12
RULE = "ChainCode is expected to be 32 bytes in size"
ATTRIBUTES = c.bstr(c.cmap([]))
CASES = []


def bootstrap(length, chain=0x92):
    """A bootstrap witness whose chain code is `length` bytes of `chain`."""
    return c.array([c.bstr(c.h(0x91, 32)), c.bstr(c.h(0xA1, 64)), c.bstr(c.h(chain, length)), ATTRIBUTES])


def witnesses(*bootstraps, vkey=False):
    fields = ([(c.uint(0), c.array([c.vkey_witness()]))] if vkey else []) + [(c.uint(2), c.array(list(bootstraps)))]
    return c.cmap(fields)


def transaction(era, wits):
    body = d.body(era)
    return c.array([body, wits, c.NULL] if VERSION[era] < VERSION["alonzo"] else [body, wits, c.TRUE, c.NULL])


def block_body(era, wits):
    """One transaction, no auxiliary data and, from Alonzo, no invalid transaction."""
    parts = [c.array([d.body(era)]), c.array([wits]), c.cmap([])]
    if VERSION[era] >= VERSION["alonzo"]:
        parts.append(c.array([]))
    return b"".join(parts)


def case(name, era, kind, version, lengths, raw):
    refused = version >= 12 and any(n != SIZE for n in lengths)
    entry = {
        "id": c.case_id(name),
        "name": name,
        "era": era,
        "kind": kind,
        "version": version,
        "lengths": lengths,
        "rule": RULE if refused else None,
        "bytes": raw.hex(),
    }
    CASES.append(entry)


for era in ERAS:
    for n in LENGTHS:
        case(f"{era} witnesses: chain code of {n} bytes", era, "witnesses", VERSION[era], [n], witnesses(bootstrap(n)))
    for n in LENGTHS:
        raw = transaction(era, witnesses(bootstrap(n), vkey=True))
        case(f"{era} transaction: chain code of {n} bytes", era, "transaction", VERSION[era], [n], raw)
    for n in LENGTHS:
        raw = block_body(era, witnesses(bootstrap(n), vkey=True))
        case(f"{era} block body: chain code of {n} bytes", era, "blockbody", VERSION[era], [n], raw)

for pair in ([SIZE, SIZE + 1], [SIZE - 1, SIZE + 1]):
    raw = witnesses(*(bootstrap(n) for n in pair))
    case(f"conway witnesses: two witnesses with chain codes of {pair[0]} and {pair[1]} bytes", "conway", "witnesses",
         VERSION["conway"], pair, raw)

for version in range(2, 13):
    for n in LENGTHS:
        case(f"bootstrap witness alone at version {version}: chain code of {n} bytes", ERA_AT.get(version), "bootstrap",
             version, [n], bootstrap(n))


def harness_input(entry):
    """What the harness reads: the era's readers report ids and key hashes; a witness alone its version."""
    if entry["kind"] == "bootstrap":
        return {"name": entry["name"], "era": entry["era"] or "none", "kind": "bootstrap",
                "bytes": entry["bytes"], "decoderVersion": entry["version"]}
    return {"name": entry["name"], "era": entry["era"], "kind": entry["kind"], "bytes": entry["bytes"],
            "ids": True, "bootstraps": True}


def merge(reference):
    """The corpus lines: each case with the verdict the harness recorded for it (absent fields are null)."""
    assert len(reference) == len(CASES), "the harness answered a different number of cases"
    lines = []
    for case_, verdict in zip(CASES, reference):
        assert (verdict["name"], verdict["kind"]) == (case_["name"], case_["kind"])
        result = verdict["result"]
        assert "unsupported" not in result, result
        line = dict(case_)
        line.update(
            {
                "accepted": result["accepted"],
                "failure": result.get("failure"),
                "keyHashes": [result["keyHash"]] if "keyHash" in result else result.get("bootstrap"),
            }
        )
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
            json.dump([harness_input(x) for x in CASES], f, indent=1)
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
