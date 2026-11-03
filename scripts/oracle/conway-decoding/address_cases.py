#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the address header oracle (#723).

An address's first byte names its type (the high four bits) and, for a Shelley address, its
network (the low four). The reference refuses a Shelley address whose header sets a bit of
`headerNonShelleyBits = 0b10001110` (`decodeAddrStateLenientT`,
`libs/cardano-ledger-core/src/Cardano/Ledger/Address.hs:655-675`, cardano-ledger f649f975): only
the low bit names the network (`headerNetworkId`, `:557-561`), and bits 1 to 3 must be clear. A
bootstrap address is the one header `0b10000010` (`isByronAddress`, `:548-552`). A reward account's
header must be `0b111x000y` (`headerIsAccountAddress`, `:930-932`). Every era reads an output's
address with `fromCborBothAddr` (`:459-477`: the backwards reader below version 7, the rigorous one
from it) and a reward account with `fromCborAccountAddress` (`:913-916`), all through these checks.

Each era from Shelley to Conway gets:
- every header of an address alone (`kind` "address", `DecCBOR Addr`): sixteen types and sixteen
  low nibbles, each with the payload of its type (a base address's two hashes, a pointer's hash and
  pointer, an enterprise address's hash, a bootstrap address's CBOR after its first byte);
- every header of a reward account alone (`kind` "account", `DecCBOR AccountAddress`), sixteen
  types and sixteen nibbles over one 28-byte hash;
- bodies that hold an address or an account with networks 1 and 2 in each place a body reads one:
  an output, a withdrawal, a pool registration's reward account, from Babbage a collateral return
  and in Conway a proposal's return account and a treasury withdrawal.

The corpus `fixtures/reference/address-headers/cases.jsonl` holds one line per era, kind and type
for the alone cases (the sixteen verdicts of its nibbles in order, the bytes of nibble 0, the
failure with its header left out; see `merge`), and one line per body case:

    python3 scripts/oracle/conway-decoding/address_cases.py --inputs /tmp/address-inputs.json
    docker run --rm --network none --read-only -v /tmp:/fixtures:ro \\
        scarab-conway-decoding:local /fixtures/address-inputs.json > /tmp/address-reference.json
    python3 scripts/oracle/conway-decoding/address_cases.py --inputs /tmp/address-inputs.json \\
        --reference /tmp/address-reference.json --out fixtures/reference/address-headers/cases.jsonl
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
PAYMENT = c.h(0x22, 28)
STAKE = c.h(0x23, 28)
POINTER = bytes([1, 2, 3])
BYRON = c.byron_address()


def since(era, first):
    return ERAS.index(era) >= ERAS.index(first)


def address(kind, nibble):
    """The address of header `kind << 4 | nibble`, with the payload its type reads."""
    header = bytes([kind << 4 | nibble])
    if kind <= 3:
        return header + PAYMENT + STAKE
    if kind <= 5:
        return header + PAYMENT + POINTER
    if kind == 8:
        return header + BYRON[1:]
    return header + PAYMENT


def account(kind, nibble):
    return bytes([kind << 4 | nibble]) + STAKE


def case(era, kind, name, raw):
    CASES.append({"name": f"{era} {kind} {name}", "era": era, "kind": kind, "bytes": raw.hex()})


def output(addr):
    return c.array([c.bstr(addr), c.uint(2_000_000)])


def pool(reward):
    return c.array(
        [
            c.uint(3),
            c.bstr(c.h(0x61, 28)),
            c.bstr(c.h(0x63, 32)),
            c.uint(1),
            c.uint(340_000_000),
            c.tag(30, c.array([c.uint(1), c.uint(2)])),
            c.bstr(reward),
            c.sset([c.bstr(c.h(0x62, 28))]),
            c.array([]),
            c.NULL,
        ]
    )


def proposal(era, reward, withdrawal):
    action = (c.array([c.uint(2), c.cmap([(c.bstr(withdrawal), c.uint(5))]), c.NULL])
              if withdrawal else c.array([c.uint(6)]))
    return c.array([c.uint(1_000_000), c.bstr(reward), action, d.anchor(d.definite)])


def body_sites(era):
    """(place, function of a network nibble to the body field map)."""
    sites = [
        ("output enterprise", lambda n: {1: c.array([output(address(6, n))])}),
        ("output base", lambda n: {1: c.array([output(address(0, n))])}),
        ("output pointer", lambda n: {1: c.array([output(address(4, n))])}),
        ("withdrawal", lambda n: {5: c.cmap([(c.bstr(account(14, n)), c.uint(5))])}),
        ("pool reward account", lambda n: {4: c.array([pool(account(14, n))])}),
    ]
    if since(era, "babbage"):
        sites.append(("collateral return", lambda n: {16: output(address(6, n))}))
    if era == "conway":
        sites.append(("proposal return account", lambda n: {20: c.array([proposal(era, account(14, n), None)])}))
        sites.append(("treasury withdrawal", lambda n: {20: c.array([proposal(era, account(14, 1), account(14, n))])}))
    return sites


for era in ERAS:
    for kind in range(16):
        for nibble in range(16):
            case(era, "address", f"type {kind} network {nibble}", c.bstr(address(kind, nibble)))
    for kind in range(16):
        for nibble in range(16):
            case(era, "account", f"type {kind} network {nibble}", c.bstr(account(kind, nibble)))
    for place, build in body_sites(era):
        for nibble in (1, 2):
            case(era, "body", f"{place} network {nibble}", d.body(era, build(nibble)))


def shown(header):
    """A header as the reference's failure shows it: `show (Header w)`, `0b` and its binary digits."""
    return "0b" + format(header, "b")


def merge(reference):
    """The corpus lines: a line per era, kind and type of the alone cases, a line per body case.

    The sixteen alone cases of a type differ in their header byte only, and so do their failures:
    a line keeps the verdicts of the sixteen nibbles in order (`accepted`), the bytes of nibble 0
    (`bytes`) and the failure once, with `{header}` where the failure shows the header
    (`failure`); a failure that is not that one with its header is kept whole (`failures`).
    """
    assert len(reference) == len(CASES), "the harness answered a different number of cases"
    lines = []
    groups = {}
    for case_, verdict in zip(CASES, reference):
        assert (verdict["name"], verdict["era"], verdict["kind"]) == (case_["name"], case_["era"], case_["kind"])
        result = verdict["result"]
        assert "unsupported" not in result, case_["name"]
        if case_["kind"] == "body":
            line = {"name": case_["name"], "era": case_["era"], "kind": "body", "accepted": result["accepted"],
                    "failure": result.get("failure"), "bytes": case_["bytes"]}
            lines.append({k: v for k, v in line.items() if v is not None})
            continue
        raw = bytes.fromhex(case_["bytes"])
        header = raw[2] if raw[0] in (0x58, 0x59) else raw[1]
        key = (case_["era"], case_["kind"], header >> 4)
        group = groups.get(key)
        if group is None:
            group = {"name": f"{case_['era']} {case_['kind']} type {header >> 4}", "era": case_["era"],
                     "kind": case_["kind"], "type": header >> 4, "accepted": [], "bytes": case_["bytes"]}
            groups[key] = group
            lines.append(group)
        assert len(group["accepted"]) == header & 0x0F, case_["name"]
        group["accepted"].append(result["accepted"])
        failure = result.get("failure")
        if failure is not None:
            template = failure.replace(shown(header), "{header}")
            if "failure" not in group:
                group["failure"] = template
            elif template != group["failure"]:
                group.setdefault("failures", {})[str(header & 0x0F)] = failure
    return [json.dumps(line, separators=(",", ":")) for line in lines]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", help="write the array of original encodings the harness reads")
    parser.add_argument("--reference", help="the harness output for those inputs, to merge")
    parser.add_argument("--out", help="write the corpus lines (needs --reference)")
    args = parser.parse_args()
    names = [x["name"] for x in CASES]
    assert len(names) == len(set(names)), "case names must be unique"
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
