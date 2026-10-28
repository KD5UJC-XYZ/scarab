#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the definite-text decoding oracle (#435).

Every text field of a block body is read with `decodeString` (cborg, through
`instance DecCBOR Text` in `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/DecCBOR.hs`,
cardano-ledger f649f975), which takes a definite-length string in every decoder version: an
indefinite (chunked) string is not text there. `Url` and `DnsName` read it through `textDecCBOR`
(`BaseTypes.hs:644-668, 678-697`), so a pool metadata url, a relay DNS name and a governance anchor
url are all definite. Transaction metadata (`decodeMetadatum`, `Metadata.hs:153-222`) and Plutus
`Data` read chunked strings in every version and stay accepted.

Each place is written twice, with a definite string (accepted) and with the same characters as one
indefinite chunk (refused with cborg's "expected string"), so a refusal is for the form of the
string alone. One chunked metadata text is included as the contrast that stays accepted, in the
whole-transaction form #373 uses.

The relay DNS name is not in this corpus: a registration's relays are kept as their original bytes
and are decoded with the body only under #436, so a chunked name there is refused by
`PoolRelays.decode` (which this change also fixes) but not yet by the body decoder. #436 adds those
cases when it lands.

The committed corpus is one file, `fixtures/reference/definite-text/cases.jsonl`, a line per case
with the original encoding and the verdict of the harness. Two steps write it: `--inputs` writes
the array the harness reads, and `--out` merges the harness output (the verdicts, in the same
order) into the lines:

    python3 scripts/oracle/conway-decoding/definite_text_cases.py --inputs /tmp/definite-text-inputs.json
    python3 scripts/oracle/conway-decoding/definite_text_cases.py --inputs /tmp/definite-text-inputs.json \\
        --reference /tmp/definite-text-reference.json --out fixtures/reference/definite-text/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402  (the CBOR helpers)
import definite_bytes_cases as d  # noqa: E402  (the body builder and the chunked-text writer)

ERAS = ["shelley", "allegra", "mary", "alonzo", "babbage", "conway"]
ORDER = {era: i for i, era in enumerate(ERAS)}
# cborg's failure for a chunked string where `decodeString` reads one.
TEXT = "expected string"
# Scarab's error case for a chunked string read as text: the ledger error. (The relay's own case,
# `IndefiniteDnsName`, is refused by `PoolRelays.decode` and reaches the body only under #436.)
URL_CASE = "IndefiniteLength"
CASES = []


def case(name, era, kind, raw, expect=None, rule=None):
    CASES.append(
        {
            "id": c.case_id(name),
            "name": name,
            "era": era,
            "kind": kind,
            "rule": rule,
            "expect": expect,
            "bytes": raw.hex(),
        }
    )


def since(era, first):
    return ORDER[era] >= ORDER[first]


POOL_URL = "https://example.invalid/pool.json"
ANCHOR_URL = "https://example.invalid/a"


def pool_cert(relays, url):
    """A pool registration (`[3, operator, vrf, pledge, cost, margin, account, owners, relays, metadata]`)."""
    return c.array(
        [
            c.uint(3),
            c.bstr(c.h(0x61, 28)),
            c.bstr(c.h(0x63, 32)),
            c.uint(1),
            c.uint(340_000_000),
            c.tag(30, c.array([c.uint(1), c.uint(2)])),
            c.bstr(b"\xe0" + c.h(0x64, 28)),
            c.sset([c.bstr(c.h(0x62, 28))]),
            relays,
            c.array([url, c.bstr(c.h(0x65, 32))]),
        ]
    )


def certs(cert):
    return c.array([cert])


ENCODINGS = (("definite", c.text, None, None), ("indefinite", d.text_chunked, TEXT, None))


# --- every era: a pool registration's metadata url and relay names ------------------------------
for era in ERAS:
    for label, encode, rule, _ in ENCODINGS:
        expect = URL_CASE if rule else None
        case(
            f"{era} body pool registration metadata url: {label}",
            era,
            "body",
            d.body(era, {4: certs(pool_cert(c.array([]), encode(POOL_URL)))}),
            expect,
            rule,
        )

# --- Conway: the governance anchors -----------------------------------------------------------------
DREP_CREDENTIAL = c.array([c.uint(0), c.bstr(c.h(0x6A, 28))])


def drep_registration(url):
    """`[16, credential, deposit, anchor]`."""
    return c.array(
        [c.uint(16), DREP_CREDENTIAL, c.uint(500_000_000), c.array([url, c.bstr(c.h(0x44, 32))])]
    )


def proposal(url):
    """`[deposit, reward account, action, anchor]` at body field 20."""
    return c.array(
        [
            c.uint(1_000_000),
            c.bstr(b"\xe1" + c.h(0x55, 28)),
            c.array([c.uint(6)]),
            c.array([url, c.bstr(c.h(0x44, 32))]),
        ]
    )


def votes(url):
    """A vote with an anchor at body field 19: `{voter: {action: [vote, anchor]}}`."""
    vote = c.array([c.uint(1), c.array([url, c.bstr(c.h(0x44, 32))])])
    return c.cmap([(c.VOTER, c.cmap([(c.ACTION, vote)]))])


for label, encode, rule, _ in ENCODINGS:
    expect = URL_CASE if rule else None
    case(
        f"conway body drep registration anchor url: {label}",
        "conway",
        "body",
        d.body("conway", {4: certs(drep_registration(encode(ANCHOR_URL)))}),
        expect,
        rule,
    )
    case(
        f"conway body proposal anchor url: {label}",
        "conway",
        "body",
        d.body("conway", {20: c.sset([proposal(encode(ANCHOR_URL))])}),
        expect,
        rule,
    )
    case(
        f"conway body vote anchor url: {label}",
        "conway",
        "body",
        d.body("conway", {19: votes(encode(ANCHOR_URL))}),
        expect,
        rule,
    )

# --- the contrast that stays accepted: metadata text ------------------------------------------------
def transaction(aux):
    return c.array([d.body("conway"), c.cmap([]), c.TRUE, aux])


case(
    "conway transaction metadata: text, one chunk",
    "conway",
    "transaction",
    transaction(c.cmap([(c.uint(674), d.text_chunked("hello"))])),
)


def merge(reference):
    """The corpus lines: each case with the verdict the harness recorded for it (absent fields are null)."""
    assert len(reference) == len(CASES), "the harness answered a different number of cases"
    lines = []
    for case_, verdict in zip(CASES, reference):
        assert (verdict["name"], verdict["era"], verdict["kind"]) == (
            case_["name"],
            case_["era"],
            case_["kind"],
        )
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
