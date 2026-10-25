#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the definite-bytes decoding oracle (#373).

Below decoder version 12 the reference reads a byte string with `decodeBytes` or `decodeByteArray`
over a definite-length string only (`ifDecoderVersionAtLeast (natVersion @12)`,
`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs`, cardano-ledger f649f975):
an indefinite (chunked) string is not a byte string there. Every era this corpus covers (Shelley 2
to Conway 9) is below 12. Two readers take chunked strings in every version and stay accepted:
Plutus `Data` (datums, redeemer data, the content of an inline datum) and transaction metadata
(`decodeMetadatum`, `libs/cardano-ledger-core/src/Cardano/Ledger/Metadata.hs`), whose 64-byte bound
holds for the joined string.

Each place is written twice, with a definite string (accepted) and with the same bytes as one
indefinite chunk (refused), so a refusal is for the form of the string alone.

    python3 scripts/oracle/conway-decoding/definite_bytes_cases.py --out fixtures/reference/definite-bytes/cases.json
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402  (shares the CBOR helpers; its own cases are not written here)

ERAS = ["shelley", "allegra", "mary", "alonzo", "babbage", "conway"]
ORDER = {era: i for i, era in enumerate(ERAS)}
# cborg's failure for a chunked string where `decodeBytes` (`ConsumeBytes`) or `decodeByteArray`
# (`ConsumeByteArray`, a `ShortByteString`) reads one.
BYTES = "expected bytes"
ARRAY = "expected string"
CASES = []


def since(era, first):
    return ORDER[era] >= ORDER[first]


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


def definite(b):
    return c.bstr(b)


def chunked(b, chunks=1):
    """`b` as an indefinite byte string of `chunks` definite chunks (the last one takes the rest)."""
    size = -(-len(b) // chunks) if b else 0
    parts = [b[i * size : (i + 1) * size] for i in range(chunks - 1)] + [b[(chunks - 1) * size :]]
    return b"\x5f" + b"".join(c.bstr(p) for p in parts) + b"\xff"


def text_chunked(s, chunks=1):
    raw = s.encode()
    size = -(-len(raw) // chunks)
    parts = [raw[i * size : (i + 1) * size] for i in range(chunks - 1)] + [raw[(chunks - 1) * size :]]
    return b"\x7f" + b"".join(c.head(3, len(p)) + p for p in parts) + b"\xff"


ADDRESS = b"\x61" + c.h(0x22, 28)
ACCOUNT = b"\xe1" + c.h(0x77, 28)
SCRIPT = c.SCRIPT[1:]


def body(era, extra=None):
    fields = {0: c.sset([c.txin(1)]), 1: c.array([c.OUTPUT]), 2: c.uint(200_000)}
    if era == "shelley":
        fields[3] = c.uint(1_000)
    fields.update(extra or {})
    return c.cmap([(c.uint(k), fields[k]) for k in sorted(fields)])


def wits(key, value):
    return c.cmap([(c.uint(key), value)])


def input_with(b):
    return c.array([b(c.h(0x11, 32)), c.uint(0)])


def output_map(fields):
    return c.cmap([(c.uint(k), v) for k, v in fields])


def map_output(era, b, at):
    """A map-form output whose field `at` takes the string `b`."""
    fields = {
        0: definite(ADDRESS),
        1: c.uint(2_000_000),
    }
    if at == "address":
        fields[0] = b(ADDRESS)
    elif at == "datum hash":
        fields[2] = c.array([c.uint(0), b(c.h(0x58, 32))])
    elif at == "inline datum":
        fields[2] = c.array([c.uint(1), c.tag(24, b(c.uint(1)))])
    elif at == "reference script":
        fields[3] = c.tag(24, b(c.array([c.uint(1), definite(SCRIPT)])))
    elif at == "reference Plutus script":
        fields[3] = c.tag(24, definite(c.array([c.uint(1), b(SCRIPT)])))
    elif at == "reference native script key hash":
        fields[3] = c.tag(24, definite(c.array([c.uint(0), c.array([c.uint(0), b(c.KEY_HASH)])])))
    return output_map(sorted(fields.items()))


def multiasset(b, at):
    policy = b(c.h(0x88, 28)) if at == "policy" else definite(c.h(0x88, 28))
    name = b(b"a") if at == "asset name" else definite(b"a")
    return c.cmap([(policy, c.cmap([(name, c.uint(1))]))])


def cred(b):
    return c.array([c.uint(0), b(c.KEY_HASH)])


def pool(b, at):
    def field(name, raw):
        return b(raw) if at == name else definite(raw)

    return c.array(
        [
            c.uint(3),
            field("operator", c.h(0x61, 28)),
            field("vrf key hash", c.h(0x63, 32)),
            c.uint(1),
            c.uint(340_000_000),
            c.tag(30, c.array([c.uint(1), c.uint(2)])),
            field("reward account", b"\xe0" + c.h(0x64, 28)),
            c.sset([field("owner", c.h(0x62, 28))]),
            c.array([]),
            c.array([c.text("https://example.invalid/pool.json"), field("metadata hash", c.h(0x65, 32))]),
        ]
    )


def anchor(b):
    return c.array([c.text("https://example.invalid/a"), b(c.h(0x44, 32))])


def proposal(b, at):
    account = b(ACCOUNT) if at == "reward account" else definite(ACCOUNT)
    action = c.array([c.uint(6)])
    if at == "previous action id":
        action = c.array([c.uint(3), c.array([b(c.h(0x66, 32)), c.uint(0)])])
    elif at == "withdrawal account":
        action = c.array([c.uint(2), c.cmap([(b(ACCOUNT), c.uint(5))]), c.NULL])
    elif at == "policy hash":
        action = c.array([c.uint(2), c.cmap([(definite(ACCOUNT), c.uint(5))]), b(c.h(0x67, 28))])
    return c.array([c.uint(1_000_000), account, action, anchor(b if at == "anchor hash" else definite)])


def votes(b, at):
    voter = c.array([c.uint(2), b(c.KEY_HASH) if at == "voter hash" else definite(c.KEY_HASH)])
    action = c.array([b(c.h(0x66, 32)) if at == "action id" else definite(c.h(0x66, 32)), c.uint(0)])
    vote_anchor = anchor(b) if at == "anchor hash" else c.NULL
    return c.cmap([(voter, c.cmap([(action, c.array([c.uint(1), vote_anchor]))]))])


def update(b, at):
    key = b(c.h(0x5A, 28)) if at == "genesis key hash" else definite(c.h(0x5A, 28))
    return c.array([c.cmap([(key, c.cmap([]))]), c.uint(10)])


def body_sites(era):
    """(place, rule, function of a string encoder to the body field map)."""
    sites = [
        ("input id", BYTES, lambda b: {0: c.sset([input_with(b)])}),
        ("output address", ARRAY, lambda b: {1: c.array([c.array([b(ADDRESS), c.uint(2_000_000)])])}),
        ("auxiliary data hash", BYTES, lambda b: {7: b(c.h(0x55, 32))}),
        ("withdrawal account", ARRAY, lambda b: {5: c.cmap([(b(ACCOUNT), c.uint(5))])}),
        ("stake registration credential", BYTES, lambda b: {4: c.array([c.array([c.uint(0), cred(b)])])}),
        (
            "stake delegation pool id",
            BYTES,
            lambda b: {4: c.array([c.array([c.uint(2), cred(definite), b(c.h(0x61, 28))])])},
        ),
        ("pool retirement pool id", BYTES, lambda b: {4: c.array([c.array([c.uint(4), b(c.h(0x61, 28)), c.uint(5)])])}),
    ]
    for at, rule in (
        ("operator", BYTES),
        ("vrf key hash", BYTES),
        ("reward account", ARRAY),
        ("owner", BYTES),
        ("metadata hash", ARRAY),
    ):
        sites.append((f"pool registration {at}", rule, lambda b, at=at: {4: c.array([pool(b, at)])}))
    if not since(era, "conway"):
        for at, rule, size in (("genesis key", BYTES, 28), ("delegate", BYTES, 28), ("vrf key hash", BYTES, 32)):
            sites.append(
                (
                    f"genesis delegation {at}",
                    rule,
                    lambda b, at=at: {
                        4: c.array(
                            [
                                c.array(
                                    [
                                        c.uint(5),
                                        b(c.h(0x5A, 28)) if at == "genesis key" else definite(c.h(0x5A, 28)),
                                        b(c.h(0x5C, 28)) if at == "delegate" else definite(c.h(0x5C, 28)),
                                        b(c.h(0x5D, 32)) if at == "vrf key hash" else definite(c.h(0x5D, 32)),
                                    ]
                                )
                            ]
                        )
                    },
                )
            )
        sites.append(
            (
                "instantaneous reward credential",
                BYTES,
                lambda b: {4: c.array([c.array([c.uint(6), c.array([c.uint(0), c.cmap([(cred(b), c.uint(5))])])])])},
            )
        )
        sites.append(("update genesis key hash", BYTES, lambda b: {6: update(b, "genesis key hash")}))
    if since(era, "mary"):
        sites.append(("mint policy", BYTES, lambda b: {9: multiasset(b, "policy")}))
        sites.append(("mint asset name", ARRAY, lambda b: {9: multiasset(b, "asset name")}))
        for at, rule in (("policy", BYTES), ("asset name", ARRAY)):
            sites.append(
                (
                    f"output value {at}",
                    rule,
                    lambda b, at=at: {
                        1: c.array([c.array([definite(ADDRESS), c.array([c.uint(2_000_000), multiasset(b, at)])])])
                    },
                )
            )
    if since(era, "alonzo"):
        sites.append(("script data hash", BYTES, lambda b: {11: b(c.h(0x56, 32))}))
        sites.append(("required signer", BYTES, lambda b: {14: c.sset([b(c.h(0x57, 28))])}))
        sites.append(("collateral input id", BYTES, lambda b: {13: c.sset([input_with(b)])}))
        sites.append(
            (
                "output datum hash",
                BYTES,
                lambda b: {1: c.array([c.array([definite(ADDRESS), c.uint(2_000_000), b(c.h(0x58, 32))])])},
            )
        )
    if since(era, "babbage"):
        sites.append(("reference input id", BYTES, lambda b: {18: c.sset([input_with(b)])}))
        sites.append(("collateral return address", ARRAY, lambda b: {16: c.array([b(ADDRESS), c.uint(2_000_000)])}))
        for at, rule in (
            ("address", ARRAY),
            ("datum hash", BYTES),
            ("inline datum", BYTES),
            ("reference script", BYTES),
            ("reference Plutus script", ARRAY),
            ("reference native script key hash", BYTES),
        ):
            sites.append((f"map output {at}", rule, lambda b, at=at: {1: c.array([map_output(era, b, at)])}))
    if since(era, "conway"):
        sites += [
            (
                "vote delegation drep hash",
                BYTES,
                lambda b: {4: c.array([c.array([c.uint(9), cred(definite), c.array([c.uint(0), b(c.h(0x68, 28))])])])},
            ),
            (
                "committee hot credential",
                BYTES,
                lambda b: {4: c.array([c.array([c.uint(14), cred(definite), c.array([c.uint(0), b(c.h(0x69, 28))])])])},
            ),
            (
                "drep registration anchor hash",
                BYTES,
                lambda b: {4: c.array([c.array([c.uint(16), cred(definite), c.uint(500), anchor(b)])])},
            ),
        ]
        for at, rule in (
            ("reward account", ARRAY),
            ("anchor hash", BYTES),
            ("previous action id", BYTES),
            ("withdrawal account", ARRAY),
            ("policy hash", BYTES),
        ):
            sites.append((f"proposal {at}", rule, lambda b, at=at: {20: c.array([proposal(b, at)])}))
        for at in ("voter hash", "action id", "anchor hash"):
            sites.append((f"vote {at}", BYTES, lambda b, at=at: {19: votes(b, at)}))
    return sites


def witness_sites(era):
    sites = [
        ("vkey", BYTES, lambda b: wits(0, c.array([c.array([b(c.h(0x90, 32)), definite(c.h(0xA0, 64))])]))),
        ("signature", BYTES, lambda b: wits(0, c.array([c.array([definite(c.h(0x90, 32)), b(c.h(0xA0, 64))])]))),
        ("native script key hash", BYTES, lambda b: wits(1, c.array([c.array([c.uint(0), b(c.KEY_HASH)])]))),
    ]
    for at, raw in (("vkey", c.h(0x91, 32)), ("signature", c.h(0xA1, 64)), ("chain code", c.h(0x92, 32)), ("attributes", c.cmap([]))):
        rule = ARRAY if at in ("chain code", "attributes") else BYTES
        parts = [c.h(0x91, 32), c.h(0xA1, 64), c.h(0x92, 32), c.cmap([])]

        def witness(b, at=at):
            names = ["vkey", "signature", "chain code", "attributes"]
            return wits(2, c.array([c.array([b(p) if n == at else definite(p) for n, p in zip(names, parts)])]))

        sites.append((f"bootstrap {at}", rule, witness))
    languages = [("Plutus V1 script", 3, "alonzo"), ("Plutus V2 script", 6, "babbage"), ("Plutus V3 script", 7, "conway")]
    for label, key, first in languages:
        if since(era, first):
            sites.append((label, ARRAY, lambda b, key=key: wits(key, c.array([b(SCRIPT)]))))
    return sites


def emit(era, kind, place, rule, build):
    case(f"{era} {kind} {place}: definite", era, kind, build(definite))
    case(f"{era} {kind} {place}: indefinite", era, kind, build(chunked), "IndefiniteLength", rule)


for era in ERAS:
    for place, rule, fields in body_sites(era):
        emit(era, "body", place, rule, lambda b, fields=fields: body(era, fields(b)))
    for place, rule, build in witness_sites(era):
        emit(era, "witnesses", place, rule, build)
    case(
        f"{era} body input id: two chunks",
        era,
        "body",
        body(era, {0: c.sset([input_with(lambda x: chunked(x, 2))])}),
        "IndefiniteLength",
        BYTES,
    )
    case(
        f"{era} body input id: an empty chunk before the bytes",
        era,
        "body",
        body(era, {0: c.sset([c.array([b"\x5f\x40" + c.bstr(c.h(0x11, 32)) + b"\xff", c.uint(0)])])}),
        "IndefiniteLength",
        BYTES,
    )
    # Plutus `Data` reads chunked byte strings in every version (each chunk at most 64 bytes).
    if since(era, "alonzo"):
        long = c.h(0x0D, 100)
        for label, data in (
            ("one chunk", chunked(b"\x01\x02")),
            ("two chunks of a 100-byte string", chunked(long, 2)),
            ("inside a constructor", c.tag(121, c.array([chunked(b"\x01\x02")]))),
        ):
            case(f"{era} witnesses datum: chunked bytes, {label}", era, "witnesses", wits(4, c.array([data])))
        case(
            f"{era} witnesses redeemer data: chunked bytes",
            era,
            "witnesses",
            wits(5, c.array([c.array([c.uint(0), c.uint(0), chunked(b"\x01"), c.array([c.uint(1), c.uint(2)])])])),
        )
    if since(era, "babbage"):
        inline = output_map(
            [
                (0, definite(ADDRESS)),
                (1, c.uint(2_000_000)),
                (2, c.array([c.uint(1), c.tag(24, definite(chunked(b"\x01\x02")))])),
            ]
        )
        case(f"{era} body map output inline datum: chunked bytes in the datum", era, "body", body(era, {1: c.array([inline])}))


# --- whole Conway transactions: auxiliary data ---------------------------------------------------
METADATA_BYTES = "bytestring exceeds 64 bytes"
METADATA_TEXT = "text exceeds 64 bytes"


def transaction(aux):
    return c.array([body("conway"), c.cmap([]), c.TRUE, aux])


def metadata(value):
    return c.cmap([(c.uint(674), value)])


for label, value, refusal in (
    ("bytes, one chunk", chunked(b"\x01\x02"), None),
    ("bytes, chunks of 64 bytes in all", chunked(c.h(0x0E, 64), 2), None),
    ("bytes, chunks of 80 bytes in all", chunked(c.h(0x0E, 80), 2), ("NumberOutOfRange", METADATA_BYTES)),
    ("bytes, a definite string of 65 bytes", definite(c.h(0x0E, 65)), ("NumberOutOfRange", METADATA_BYTES)),
    ("text, one chunk", text_chunked("hello"), None),
    ("text, chunks of 80 bytes in all", text_chunked("x" * 80, 2), ("NumberOutOfRange", METADATA_TEXT)),
):
    expect, rule = refusal if refusal else (None, None)
    case(f"conway transaction metadata: {label}", "conway", "transaction", transaction(metadata(value)), expect, rule)

for place, rule, scripts in (
    ("native script key hash", BYTES, lambda b: (1, c.array([c.array([c.uint(0), b(c.KEY_HASH)])]))),
    ("Plutus V1 script", ARRAY, lambda b: (2, c.array([b(SCRIPT)]))),
):

    def aux(b, scripts=scripts):
        key, value = scripts(b)
        return c.tag(259, c.cmap([(c.uint(key), value)]))

    case(f"conway transaction auxiliary {place}: definite", "conway", "transaction", transaction(aux(definite)))
    case(
        f"conway transaction auxiliary {place}: indefinite",
        "conway",
        "transaction",
        transaction(aux(chunked)),
        "IndefiniteLength",
        rule,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    names = [x["name"] for x in CASES]
    assert len(names) == len(set(names)), "case names must be unique"
    ids = [x["id"] for x in CASES]
    assert len(ids) == len(set(ids)), "case ids must be unique"
    with open(args.out, "w") as f:
        json.dump(CASES, f, indent=1)
        f.write("\n")


if __name__ == "__main__":
    main()
