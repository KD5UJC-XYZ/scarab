#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The places a transaction carries Plutus `Data`, each with data the reference reads and data it
refuses (#721): the datums of a witness set, a redeemer's data in the list form and (Conway) the map
form, and an output's inline datum (Babbage on), in an output and a collateral return. The
reference decodes each as `Data` when it decodes the body or the witness set (`decodeData`,
`PlutusCore/Data.hs:209-265`, plutus 507cab8c; `Plutus/Data.hs:97-125, 217-239`, cardano-ledger
f649f975), so data that is not `Data` fails the body or the witness set.

The committed corpus is `fixtures/reference/plutus-data-places/cases.jsonl`:

    python3 scripts/oracle/conway-decoding/data_cases.py --inputs /tmp/data-inputs.json
    docker run --rm --network none --read-only -v /tmp:/fixtures:ro \\
        scarab-conway-decoding:local /fixtures/data-inputs.json > /tmp/data-reference.json
    python3 scripts/oracle/conway-decoding/data_cases.py --inputs /tmp/data-inputs.json \\
        --reference /tmp/data-reference.json --out fixtures/reference/plutus-data-places/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402  (the CBOR helpers)
import definite_bytes_cases as d  # noqa: E402  (the era bodies)

CASES = []

# data `decodeData` reads, and data it refuses
GOOD = {
    "an integer": c.uint(1),
    "a constructor": c.tag(121, c.array([c.uint(1), c.bstr(b"ab")])),
    "an indefinite list": c.array([c.uint(1)], indef=True),
    "a map": c.cmap([(c.uint(1), c.bstr(b""))]),
    "a big number": c.tag(2, c.bstr(b"\x01" + b"\x00" * 9)),
    "a byte string of 64 bytes": c.bstr(b"\x01" * 64),
    "a chunked byte string": b"\x5f" + c.bstr(b"\x01" * 64) + c.bstr(b"\x02") + b"\xff",
}
BAD = {
    "null": c.NULL,
    "text": c.text("x"),
    "a boolean": c.TRUE,
    "a byte string of 65 bytes": c.bstr(b"\x01" * 65),
    "a constructor of no list": c.tag(121, c.uint(1)),
    "an unknown tag": c.tag(99, c.uint(1)),
    "a float": b"\xf9\x3c\x00",
}


def case(era, kind, name, raw):
    CASES.append({"name": f"{era} {kind} {name}", "era": era, "kind": kind, "bytes": raw.hex()})


def redeemer_list(data):
    return c.array([c.array([c.uint(0), c.uint(0), data, c.array([c.uint(1), c.uint(2)])])])


def redeemer_map(data):
    return c.cmap([(c.array([c.uint(0), c.uint(0)]), c.array([data, c.array([c.uint(1), c.uint(2)])]))])


def inline(data):
    return c.cmap([(c.uint(0), c.bstr(d.ADDRESS)), (c.uint(1), c.uint(2_000_000)),
                   (c.uint(2), c.array([c.uint(1), c.tag(24, c.bstr(data))]))])


for era in ["alonzo", "babbage", "conway"]:
    for label, data in [*GOOD.items(), *BAD.items()]:
        case(era, "witnesses", f"datum {label}", d.wits(4, c.array([data])))
        case(era, "witnesses", f"redeemer data {label}", d.wits(5, redeemer_list(data)))
    # the second datum is the one refused
    case(era, "witnesses", "a good datum and a null one", d.wits(4, c.array([c.uint(1), c.NULL])))
for label, data in [*GOOD.items(), *BAD.items()]:
    case("conway", "witnesses", f"redeemer map data {label}", d.wits(5, redeemer_map(data)))
for era in ["babbage", "conway"]:
    for label, data in [*GOOD.items(), *BAD.items()]:
        case(era, "body", f"inline datum {label}", d.body(era, {1: c.array([inline(data)])}))
    case(era, "body", "inline datum with bytes after the data", d.body(era, {1: c.array([inline(c.uint(1) + c.uint(2))])}))
    case(era, "body", "inline datum of no bytes", d.body(era, {1: c.array([inline(b"")])}))
    case(era, "body", "collateral return inline datum null", d.body(era, {16: inline(c.NULL)}))
    case(era, "body", "collateral return inline datum", d.body(era, {16: inline(c.uint(1))}))


def merge(reference):
    assert len(reference) == len(CASES)
    lines = []
    for case_, verdict in zip(CASES, reference):
        assert (verdict["name"], verdict["era"], verdict["kind"]) == (case_["name"], case_["era"], case_["kind"])
        result = verdict["result"]
        line = {"name": case_["name"], "era": case_["era"], "kind": case_["kind"],
                "accepted": result["accepted"], "failure": result.get("failure"), "bytes": case_["bytes"]}
        lines.append(json.dumps({k: v for k, v in line.items() if v is not None}, separators=(",", ":")))
    return lines


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--inputs")
    p.add_argument("--reference")
    p.add_argument("--out")
    a = p.parse_args()
    names = [x["name"] for x in CASES]
    assert len(names) == len(set(names))
    if a.inputs:
        with open(a.inputs, "w") as f:
            json.dump(CASES, f, indent=1)
            f.write("\n")
    if a.out:
        with open(a.reference) as f:
            lines = merge(json.load(f))
        with open(a.out, "w") as f:
            f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
