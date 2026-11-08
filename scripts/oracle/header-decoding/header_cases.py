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
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "conway-decoding"))
import cases as c  # noqa: E402  (the CBOR helpers)

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from devnet import cborspan  # noqa: E402

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


# The Leios header's cases are the Leios testnet oracle's (scripts/oracle/leios-testnet/leios_cases.py),
# whose reference is the build that testnet runs (#711); it writes them with `fields` and `header`.


# The VRF check of #712: a key of a seed and its certificate of a message, with the output replaced.
for era in ["alonzo", "conway"]:
    CASES.append({"id": c.case_id(f"{era} vrfcheck"), "name": f"{era} vrfcheck", "era": era, "kind": "vrfcheck",
                  "bytes": (c.h(0x5e, 32) + f"slot 1000 of {era}".encode()).hex()})


# #749: the reference joins a chunked string's chunks without counting them, and bounds a header by
# the ChainSync codec's bytes and, after decoding, `maxBHSize`, nothing else. Below decoder version 12
# a chunked string is refused, so a Shelley-to-Conway header has no such form; the Leios header, read
# at 12, is the Leios testnet oracle's (`leios_cases.py`).
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
original_block = bytes.fromhex((ROOT / "fixtures/byron-version/headers/original.hex").read_text())
start, end = cborspan.resolve(original_block, [1, 0])[0]
original_header = original_block[start:end]
start, end = cborspan.resolve(original_header, [2, 1])[0]
# SscProof's CertificatesProof reads and drops a byte string without a hash-size check
# (Cardano/Chain/Ssc.hs:169-188). The body-proof rules do not compare this obsolete SSC field.
large_ssc = c.array([c.uint(3), c.bstr(b"\x00" * 5000)])
case(BYRON, "mainheader", "original mainnet header", original_header)
case(BYRON, "mainheader", "SSC proof of 5000 bytes",
     original_header[:start] + large_ssc + original_header[end:])
case(BYRON, "boundaryheader", "no attributes", boundary_header([]))
case(BYRON, "boundaryheader", "200 attributes", boundary_header([(c.uint(k), c.bstr(b"")) for k in range(200)]))
case(BYRON, "boundaryheader", "four fields", boundary_header([], fields=4))
case(BYRON, "boundaryheader", "200 attributes with a chunked final value",
     boundary_header([(c.uint(k), c.bstr(b"")) for k in range(199)] +
                     [(c.uint(199), b"\x5f\x40\xff")]))


def boundary_extra(extra):
    """A Byron boundary header of epoch 5 with `extra` as its extra data (#754)."""
    return c.array([c.uint(764824073), c.bstr(c.h(0x11, 32)), c.bstr(c.h(0x22, 32)),
                    c.array([c.uint(5), c.array([c.uint(108000)])]), extra])


# #754: the extra data is a definite list of one item, `enforceSize`; the attributes a definite map
# of strictly ascending `Word8` keys to definite byte strings (`decodeMapSkel` below version 2,
# `Decoder.hs:736-765,810-823`); attribute 255 "Genesis" makes the header name the genesis.
EMPTY = c.bstr(b"")


def attrs(*pairs):
    return c.array([c.cmap(list(pairs))])


for name, extra in [
    ("extra data with a non-minimal list head", b"\x98\x01" + c.cmap([])),
    ("attributes with a non-minimal map head", c.array([b"\xb8\x01" + c.uint(1) + EMPTY])),
    ("a key with a non-minimal head", attrs((b"\x18\x05", EMPTY))),
    ("a key in nine bytes", attrs((b"\x1b" + (5).to_bytes(8, "big"), EMPTY))),
    ("keys 24 and 255", attrs((c.uint(24), EMPTY), (c.uint(255), EMPTY))),
    ("a value with a non-minimal length head", attrs((c.uint(1), b"\x58\x01\x00"))),
    ("a value of 1000 bytes", attrs((c.uint(1), c.bstr(b"\x00" * 1000)))),
    ("ascending keys with a gap", attrs((c.uint(1), EMPTY), (c.uint(7), EMPTY))),
    ("a non-minimal key below the next", attrs((b"\x18\x05", EMPTY), (c.uint(6), EMPTY))),
    ("the genesis tag", attrs((c.uint(255), c.bstr(b"Genesis")))),
    ("extra data indefinite", b"\x9f" + c.cmap([]) + b"\xff"),
    ("extra data not a list", c.cmap([])),
    ("extra data of no fields", c.array([])),
    ("extra data of two fields", c.array([c.cmap([]), c.cmap([])])),
    ("attributes not a map", c.array([c.uint(0)])),
    ("attributes an indefinite map", c.array([c.cmap([(c.uint(1), EMPTY)], indef=True)])),
    ("a key of 256", attrs((c.uint(256), EMPTY))),
    ("a negative key", attrs((b"\x20", EMPTY))),
    ("a chunked value", attrs((c.uint(1), b"\x5f\x41\x00\xff"))),
    ("a text value", attrs((c.uint(1), c.text("a")))),
    ("a tagged value", attrs((c.uint(1), b"\xc2\x40"))),
    ("duplicate keys", attrs((c.uint(1), EMPTY), (c.uint(1), c.bstr(b"a")))),
    ("descending keys", attrs((c.uint(2), EMPTY), (c.uint(1), EMPTY))),
    ("a non-minimal key above the next", attrs((c.uint(6), EMPTY), (b"\x18\x05", EMPTY))),
]:
    case(BYRON, "boundaryheader", name, boundary_extra(extra))


def splice(raw, path, new):
    """`raw` with the item at `path` replaced by `new`."""
    start, end = cborspan.resolve(raw, path)[0]
    return raw[:start] + new + raw[end:]


def item(raw, path):
    """The bytes of the item at `path`."""
    start, end = cborspan.resolve(raw, path)[0]
    return raw[start:end]


def indefinite(raw, path):
    """`raw` with the definite list or map at `path` written indefinite, its items unchanged."""
    start, end = cborspan.resolve(raw, path)[0]
    major, _, at = cborspan.head(raw, start)
    return splice(raw, path, bytes([0x9f if major == 4 else 0xbf]) + raw[at:end] + b"\xff")


W64 = 2**64 - 1
BIG = b"\xc2\x49\x01" + b"\x00" * 8  # 2^64 as a bignum: no Word64 reads it
# #634: the rest of the Byron header grammar. `decCBORAHeader` reads every list with `enforceSize`
# (definite only), the magic as a `Word32`, epoch, slot count and difficulty as `Word64`, the
# signature's kind as a `Word8`, and the extra data as `[ProtocolVersion, SoftwareVersion,
# empty attributes, bytes]` (`Block/Header.hs:351-393`, `Slotting/EpochAndSlotCount.hs:65-68`,
# `Common/ChainDifficulty.hs:47-50`, `Update/ProtocolVersion.hs:64-67`,
# `Update/SoftwareVersion.hs:73-76`, `Common/Attributes.hs:241-244`, `Crypto/ProtocolMagic.hs:58-62`).
H = original_header
for name, raw in [
    ("header indefinite", indefinite(H, [])),
    ("consensus data indefinite", indefinite(H, [3])),
    ("slot indefinite", indefinite(H, [3, 0])),
    ("difficulty indefinite", indefinite(H, [3, 2])),
    ("signature indefinite", indefinite(H, [3, 3])),
    ("signature proxy indefinite", indefinite(H, [3, 3, 1])),
    ("delegation certificate indefinite", indefinite(H, [3, 3, 1, 0])),
    ("extra data indefinite", indefinite(H, [4])),
    ("protocol version indefinite", indefinite(H, [4, 0])),
    ("software version indefinite", indefinite(H, [4, 1])),
    ("attributes an indefinite empty map", splice(H, [4, 2], b"\xbf\xff")),
    ("attributes of one entry", splice(H, [4, 2], c.cmap([(c.uint(0), EMPTY)]))),
    ("extra data of three fields", splice(H, [4], c.array([item(H, [4, i]) for i in range(3)]))),
    ("extra data of five fields", splice(H, [4], c.array([item(H, [4, i]) for i in range(4)] + [EMPTY]))),
    ("extra proof of three bytes", splice(H, [4, 3], c.bstr(b"\x00" * 3))),
    ("extra proof chunked", splice(H, [4, 3], b"\x5f" + item(H, [4, 3]) + b"\xff")),
    ("extra proof as text", splice(H, [4, 3], c.text("a"))),
    ("magic 2^32 - 1", splice(H, [0], c.uint(2**32 - 1))),
    ("magic 2^32", splice(H, [0], c.uint(2**32))),
    ("magic minus 1", splice(H, [0], c.nint(-1))),
    ("magic with a non-minimal head", splice(H, [0], c.uint(764824073, width=8))),
    ("epoch 2^64 - 1", splice(H, [3, 0, 0], c.uint(W64))),
    ("epoch 2^64", splice(H, [3, 0, 0], BIG)),
    ("slot count 2^16", splice(H, [3, 0, 1], c.uint(2**16))),
    ("slot count 2^64 - 1", splice(H, [3, 0, 1], c.uint(W64))),
    ("slot count 2^64", splice(H, [3, 0, 1], BIG)),
    ("difficulty 2^64 - 1", splice(H, [3, 2, 0], c.uint(W64))),
    ("difficulty 2^64", splice(H, [3, 2, 0], BIG)),
    ("signature kind 258", splice(H, [3, 3, 0], c.uint(258))),
    ("delegation epoch 2^64 - 1", splice(H, [3, 3, 1, 0, 0], c.uint(W64))),
    ("delegation epoch 2^64", splice(H, [3, 3, 1, 0, 0], BIG)),
    ("protocol major 2^16", splice(H, [4, 0, 0], c.uint(2**16))),
    ("protocol minor 2^16 - 1", splice(H, [4, 0, 1], c.uint(2**16 - 1))),
    ("protocol alternative 256", splice(H, [4, 0, 2], c.uint(256))),
    ("protocol version of two fields", splice(H, [4, 0], c.array([c.uint(0), c.uint(0)]))),
    ("software number 2^32 - 1", splice(H, [4, 1, 1], c.uint(2**32 - 1))),
    ("software number 2^32", splice(H, [4, 1, 1], c.uint(2**32))),
    ("application name of 13 characters", splice(H, [4, 1, 0], c.text("cardano-sl-xy"))),
    ("application name chunked", splice(H, [4, 1, 0], b"\x7f" + c.text("cardano-sl") + b"\xff")),
    ("application name as bytes", splice(H, [4, 1, 0], c.bstr(b"cardano-sl"))),
    ("software version of three fields", splice(H, [4, 1], c.array([c.text("cardano-sl"), c.uint(1), c.uint(1)]))),
]:
    case(BYRON, "mainheader", name, raw)

# A boundary header drops its magic with `dropInt32` (`Block/Header.hs:613-618`), so a negative one
# is read; its epoch is a `Word64` and its difficulty a `ChainDifficulty` (`Boundary.hs:33-38`).
E = boundary_header([])
for name, raw in [
    ("header indefinite", indefinite(E, [])),
    ("consensus data indefinite", indefinite(E, [3])),
    ("difficulty indefinite", indefinite(E, [3, 1])),
    ("magic minus 1", splice(E, [0], c.nint(-1))),
    ("magic minus 2^31", splice(E, [0], c.nint(-(2**31)))),
    ("magic minus 2^31 - 1", splice(E, [0], c.nint(-(2**31) - 1))),
    ("magic 2^31 - 1", splice(E, [0], c.uint(2**31 - 1))),
    ("magic 2^31", splice(E, [0], c.uint(2**31))),
    ("magic with a non-minimal head", splice(E, [0], c.uint(764824073, width=8))),
    ("epoch 2^64 - 1", splice(E, [3, 0], c.uint(W64))),
    ("epoch 2^64", splice(E, [3, 0], BIG)),
    ("difficulty 2^64 - 1", splice(E, [3, 1, 0], c.uint(W64))),
    ("difficulty 2^64", splice(E, [3, 1, 0], BIG)),
]:
    case(BYRON, "boundaryheader", name, raw)


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
