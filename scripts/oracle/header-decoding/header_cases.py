#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the header decoding oracle (#698).

Babbage and Conway headers are Praos headers: below decoder version 12 `HeaderBody` and `HeaderRaw`
are `RecD` records (`libs/cardano-protocol/src/Cardano/Protocol/Praos/BlockHeader.hs:203-229`,
cardano-ledger f649f975), so a non-empty indefinite list is refused with "Excess terms in array" and
an empty indefinite list `9f ff` with the fields after it is read as the record. Their operational
certificate and protocol version are read by `decodeRecordNamed` (`CBORGroup`,
`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Group.hs:27-35`), a VRF certificate by
`enforceSize`, which takes a definite list only (`Decoding/DecCBOR.hs:568-573`). Shelley to Alonzo
headers are TPraos headers, whose `BHBody` and `BHeaderRaw` read their fields inside
`decodeRecordNamed` (`libs/cardano-protocol/src/Cardano/Protocol/TPraos/BlockHeader.hs:193-241`).

The KES signature is checked over `getSignableRepresentation`, the header body encoded again at its
own major protocol version (`Praos/BlockHeader.hs:113-117`, `TPraos/BlockHeader.hs:170-171`). Every
accepted case reports those bytes, so a reader compares its own encoding with no key involved; the
cases with non-minimal heads, indefinite lists and detached records are the ones whose original
bytes differ from them. Each case is a header, a header body alone, or a ledger block holding the
header, of an era, read at that era's decoder version. A VRF output of any length decodes
(`OutputVRF`, `Decoding/DecCBOR.hs:564-565`); the "vrfcheck" cases show the released
`verifyCertified` refusing a certificate whose output is not the proof's (#712).

    python3 scripts/oracle/header-decoding/header_cases.py --inputs /tmp/header-inputs.json
    docker run --rm --network none --read-only -v /tmp:/fixtures:ro \\
        scarab-header-decoding:local /fixtures/header-inputs.json > /tmp/header-reference.json
    python3 scripts/oracle/header-decoding/header_cases.py --inputs /tmp/header-inputs.json \\
        --reference /tmp/header-reference.json --out fixtures/reference/header-decoding/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "conway-decoding"))
import cases as c  # noqa: E402  (the CBOR helpers)

CASES = []
TPRAOS = ["shelley", "alonzo"]
PRAOS = ["babbage", "conway"]
MAJOR = {"shelley": 2, "alonzo": 6, "babbage": 8, "conway": 10, "leios": 12}


def case(era, kind, name, raw):
    name = f"{era} {kind} {name}"
    CASES.append({"id": c.case_id(name), "name": name, "era": era, "kind": kind, "bytes": raw.hex()})


def items(fields, form="definite", width=None):
    """A list of `fields`: definite (with a head of `width`), indefinite, or detached (`9f ff` and
    the fields after the break)."""
    if form == "indefinite":
        return c.array(fields, indef=True)
    if form == "detached":
        return b"\x9f\xff" + b"".join(fields)
    return c.array(fields, width=width)


def nbstr(b, width=None):
    """A byte string whose length head has `width` bytes (non-minimal when given)."""
    return c.head(2, len(b), width) + b


def vrf_cert(byte, form="definite", width=None, output=64):
    """A VRF certificate whose output has `output` bytes (64 in one that verifies, #712)."""
    return items([nbstr(c.h(byte, output), width), nbstr(c.h(byte + 1, 80), width)], form)


def fields(era, prev=True, nonmin=False, ocert="definite", pv="definite", vrf="definite", major=None,
           minor=0, size=1024, announcement=None, chunked=False, empty_chunks=0, large=False, output=64):
    """The fields of a header body; `nonmin` writes every head that has a shorter form wider. A Leios
    body adds the certificate flag and the announcement, null or `announcement` (a list form and a
    size); `chunked` writes the issuer key as an indefinite byte string, and `empty_chunks` as one
    whose key follows that many empty chunks (each an item of its own to a reader that counts the
    chunks, #749); `large` writes the block
    number, slot, certificate counter and KES period at and above 2^63, the top of a `Word64`;
    `output` is the length of the VRF outputs."""
    w8 = 8 if nonmin else None
    w4 = 4 if nonmin else None
    w2 = 2 if nonmin else None
    major = MAJOR[era] if major is None else major
    head = [
        c.uint(2**64 - 1 if large else 42, w8),
        c.uint(2**63 if large else 1000, w8),
        nbstr(c.h(0x11, 32), w2) if prev else c.NULL,
        issuer_key(chunked, empty_chunks, w2),
        nbstr(c.h(0x33, 32), w2),
    ]
    cert = [nbstr(c.h(0x66, 32), w2), c.uint(2**63 + 3 if large else 3, w8), c.uint(2**63 + 7 if large else 7, w8),
            nbstr(c.h(0x77, 64), w2)]
    version = [c.uint(major, w4), c.uint(minor, w4)]
    if era in TPRAOS:
        return head + [vrf_cert(0x40, vrf, w2, output), vrf_cert(0x50, vrf, w2, output), c.uint(size, w4),
                       nbstr(c.h(0x55, 32), w2)] + cert + version
    praos = head + [vrf_cert(0x40, vrf, w2, output), c.uint(size, w4), nbstr(c.h(0x55, 32), w2),
                    items(cert, ocert, 1 if nonmin else None), items(version, pv, 1 if nonmin else None)]
    if era != "leios":
        return praos
    if announcement is None:
        return praos + [b"\xf4", c.NULL]
    form, eb_size = announcement
    return praos + [b"\xf5", items([nbstr(c.h(0x99, 32), w2), c.uint(eb_size, w4)], form, 1 if nonmin else None)]


def issuer_key(chunked, empty_chunks, width):
    """The issuer key: definite, two chunks of 16 bytes, or the key after `empty_chunks` empty ones."""
    key = c.h(0x22, 32)
    if empty_chunks:
        return b"\x5f" + b"\x40" * empty_chunks + c.bstr(key) + b"\xff"
    if chunked:
        return b"\x5f" + c.bstr(key[:16]) + c.bstr(key[16:]) + b"\xff"
    return nbstr(key, width)


SIGNATURE = c.bstr(c.h(0x88, 448))


def header(era, body_form="definite", header_form="definite", nonmin=False, **kw):
    w = 1 if nonmin else None
    body = items(fields(era, nonmin=nonmin, **kw), body_form, w)
    sig = nbstr(c.h(0x88, 448), 4) if nonmin else SIGNATURE
    return items([body, sig], header_form, w)


def block(era, head, indef=False):
    """A ledger block of `head` and one empty transaction list each, as `Block.hs:104-115` reads."""
    parts = [head, c.array([]), c.array([]), c.cmap([])]
    if era == "alonzo" or era in PRAOS:
        parts.append(c.array([]))
    return items(parts, "indefinite" if indef else "definite")


for era in TPRAOS + PRAOS:
    praos = era in PRAOS
    case(era, "header", "definite", header(era))
    case(era, "header", "genesis predecessor", header(era, prev=False))
    case(era, "header", "indefinite", header(era, header_form="indefinite"))
    case(era, "header", "detached", header(era, header_form="detached"))
    case(era, "header", "body indefinite", header(era, body_form="indefinite"))
    case(era, "header", "body detached", header(era, body_form="detached"))
    case(era, "header", "both detached", header(era, body_form="detached", header_form="detached"))
    case(era, "header", "both indefinite", header(era, body_form="indefinite", header_form="indefinite"))
    case(era, "header", "non-minimal heads", header(era, nonmin=True))
    case(era, "header", "vrf certificate indefinite", header(era, vrf="indefinite"))
    case(era, "header", "major above the last version", header(era, major=14))
    case(era, "header", "major at the last version", header(era, major=13))
    case(era, "header", "minor over a word32", header(era, minor=2**32))
    case(era, "header", "body size over a word32", header(era, size=2**32))
    case(era, "header", "word64 values at and above 2^63", header(era, large=True))
    case(era, "header", "vrf output empty", header(era, output=0))
    case(era, "header", "vrf output of 65 bytes", header(era, output=65))
    case(era, "block", "vrf output empty", block(era, header(era, output=0)))
    case(era, "header", "detached without its signature",
         b"\x9f\xff" + items(fields(era)))
    case(era, "header", "body detached one field short",
         c.array([b"\x9f\xff" + b"".join(fields(era)[:-1]), SIGNATURE]))
    case(era, "headerbody", "definite", items(fields(era)))
    case(era, "headerbody", "indefinite", items(fields(era), "indefinite"))
    case(era, "headerbody", "detached", items(fields(era), "detached"))
    case(era, "block", "definite header", block(era, header(era)))
    case(era, "block", "indefinite header", block(era, header(era, header_form="indefinite")))
    case(era, "block", "detached header", block(era, header(era, header_form="detached")))
    if praos:
        case(era, "block", "detached header body", block(era, header(era, body_form="detached")))
        case(era, "block", "both detached in an indefinite block",
             block(era, header(era, body_form="detached", header_form="detached"), indef=True))
        case(era, "header", "operational certificate indefinite", header(era, ocert="indefinite"))
        case(era, "header", "operational certificate detached", header(era, ocert="detached"))
        case(era, "header", "protocol version indefinite", header(era, pv="indefinite"))
        case(era, "header", "protocol version detached", header(era, pv="detached"))
        case(era, "header", "every list indefinite but the vrf certificate",
             header(era, body_form="indefinite", ocert="indefinite", pv="indefinite"))
        case(era, "header", "detached with indefinite groups and non-minimal heads",
             header(era, body_form="detached", header_form="detached", ocert="indefinite", pv="indefinite",
                    nonmin=True))


LEIOS = "leios"
case(LEIOS, "header", "definite", header(LEIOS))
case(LEIOS, "header", "indefinite", header(LEIOS, header_form="indefinite"))
case(LEIOS, "header", "detached", header(LEIOS, header_form="detached"))
case(LEIOS, "header", "body indefinite", header(LEIOS, body_form="indefinite"))
case(LEIOS, "header", "body detached", header(LEIOS, body_form="detached"))
case(LEIOS, "header", "both detached", header(LEIOS, body_form="detached", header_form="detached"))
case(LEIOS, "header", "non-minimal heads", header(LEIOS, nonmin=True, announcement=("definite", 5000)))
case(LEIOS, "header", "vrf certificate indefinite", header(LEIOS, vrf="indefinite"))
case(LEIOS, "header", "issuer key chunked", header(LEIOS, chunked=True))
case(LEIOS, "header", "major above the last version", header(LEIOS, major=14))
case(LEIOS, "header", "minor over a word32", header(LEIOS, minor=2**32))
case(LEIOS, "header", "announcement", header(LEIOS, announcement=("definite", 5000)))
case(LEIOS, "header", "announcement indefinite", header(LEIOS, announcement=("indefinite", 5000)))
case(LEIOS, "header", "announcement detached", header(LEIOS, announcement=("detached", 5000)))
case(LEIOS, "header", "announcement size over a word32", header(LEIOS, announcement=("definite", 2**32)))
case(LEIOS, "header", "protocol version indefinite", header(LEIOS, pv="indefinite"))
case(LEIOS, "header", "protocol version detached", header(LEIOS, pv="detached"))
case(LEIOS, "header", "operational certificate indefinite", header(LEIOS, ocert="indefinite"))
case(LEIOS, "header", "operational certificate detached", header(LEIOS, ocert="detached"))
case(LEIOS, "header", "word64 values at and above 2^63", header(LEIOS, large=True))
case(LEIOS, "header", "vrf output empty", header(LEIOS, output=0))
case(LEIOS, "header", "detached with a detached announcement and non-minimal heads",
     header(LEIOS, body_form="detached", header_form="detached", nonmin=True, announcement=("detached", 7)))
case(LEIOS, "headerbody", "definite", items(fields(LEIOS)))
case(LEIOS, "headerbody", "indefinite", items(fields(LEIOS), "indefinite"))
case(LEIOS, "headerbody", "detached", items(fields(LEIOS), "detached"))


# The VRF check of #712: a key of a seed and its certificate of a message, with the output replaced.
for era in ["alonzo", "conway"]:
    CASES.append({"id": c.case_id(f"{era} vrfcheck"), "name": f"{era} vrfcheck", "era": era, "kind": "vrfcheck",
                  "bytes": (c.h(0x5e, 32) + f"slot 1000 of {era}".encode()).hex()})


# #749: the reference joins a chunked string's chunks without counting them, and bounds a header by
# the ChainSync codec's bytes and, after decoding, `maxBHSize`, nothing else. Leios (decoder version
# 12) reads a chunked issuer key: after 244 empty chunks the header is 1100 bytes, mainnet's
# `maxBHSize`, and holds more than 256 items; after 4000 it is over 4096 bytes. Below version 12 a
# chunked string is refused, so a Shelley-to-Conway header has no such form.
LEIOS_AT_MAX_BH_SIZE = header(LEIOS, empty_chunks=244)
assert len(LEIOS_AT_MAX_BH_SIZE) == 1100
case(LEIOS, "header", "issuer key after 244 empty chunks, 1100 bytes", LEIOS_AT_MAX_BH_SIZE)
case(LEIOS, "header", "issuer key after 4000 empty chunks", header(LEIOS, empty_chunks=4000))
for era in ["alonzo", "conway"]:
    case(era, "header", "issuer key chunked", header(era, chunked=True))
    case(era, "header", "issuer key after 244 empty chunks", header(era, empty_chunks=244))


def boundary_header(attributes, fields=5):
    """A Byron epoch boundary header: protocol magic, previous hash, body proof, `[epoch,
    [difficulty]]` and `[attributes]`, a map of `Word8` to byte strings whose entries the reference
    keeps unparsed (`decCBORABoundaryHeader`, `eras/byron/ledger/impl/src/Cardano/Chain/Block/
    Header.hs:613-626`; `Boundary.hs:33-38,61-67`; `Common/Attributes.hs:213-232`); the first
    `fields` of them."""
    parts = [c.uint(764824073), c.bstr(c.h(0x11, 32)), c.bstr(c.h(0x22, 32)),
             c.array([c.uint(5), c.array([c.uint(108000)])]), c.array([c.cmap(attributes)])]
    return c.array(parts[:fields])


BYRON = "byron"
case(BYRON, "boundaryheader", "no attributes", boundary_header([]))
case(BYRON, "boundaryheader", "200 attributes", boundary_header([(c.uint(k), c.bstr(b"")) for k in range(200)]))
case(BYRON, "boundaryheader", "four fields", boundary_header([], fields=4))


def merge(reference):
    """The corpus lines: each case with the verdict, the signable bytes and the hashes."""
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
            "accepted": result["accepted"],
            "failure": result.get("failure"),
            "hash": result.get("hash"),
            "bodyHash": result.get("bodyhash"),
            "signable": result.get("signable"),
            "vrf": result.get("vrf"),
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
