#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the delegation hash length oracle.

On 21 November 2025 mainnet partitioned over a transaction whose delegation certificate named a pool
by a hash twice the length of a pool id: nodes that read a hash from a longer byte string kept its
first 28 bytes and took the delegation, nodes that required the exact length refused it (the sources
are cited in `fixtures/reference/delegation-hashes/README.md`). The reference reads a key hash, a
script hash and a pool id as `Hash Blake2b_224`, whose decoder takes a byte string of exactly 28
bytes (`DecCBOR (Hash h a)` over `DecCBOR (PackedBytes n)`,
`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/DecCBOR.hs:492-498`, cardano-ledger
f649f975).

Each Conway certificate that delegates (`DelegStake` 2, `DelegVote` 9, `DelegStakeVote` 10 and the
registering forms 11, 12 and 13, `eras/conway/impl/src/Cardano/Ledger/Conway/TxCert.hs:683-731`) is
written in a minimal whole transaction `[body, {}, true, null]` with one of its hashes one byte too
short (27) or one byte too long (29): the stake credential's key or script hash, the pool id, and the
DRep's key or script hash. Every pool id is also written twice over (56 bytes), the shape of the
incident. Each certificate is also written with every hash of 28 bytes, with key hashes and with
script hashes, as the controls that are accepted.

The committed corpus is one file, `fixtures/reference/delegation-hashes/cases.jsonl`, a line per
case with its encoding, what it varies and the verdict of the harness, written in two steps:

    python3 scripts/oracle/conway-decoding/delegation_hash_cases.py --inputs /tmp/delegation-hashes-inputs.json
    docker run --rm --network none --read-only -v /tmp:/fixtures:ro \\
        scarab-conway-decoding:local /fixtures/delegation-hashes-inputs.json > /tmp/delegation-hashes-reference.json
    python3 scripts/oracle/conway-decoding/delegation_hash_cases.py --inputs /tmp/delegation-hashes-inputs.json \\
        --reference /tmp/delegation-hashes-reference.json --out fixtures/reference/delegation-hashes/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402  (the CBOR helpers)

# cardano-crypto-class's failure for a hash of another length (`packedBytesSizeGuard`), and the
# Scarab error case that must answer it.
RULE = "Number of bytes mismatch"
EXPECT = "ByteLength"
SIZE = 28
CREDENTIAL = {"key": 0x33, "script": 0x3C}
POOL = 0x61
DREP = {"key": 0x68, "script": 0x6C}
DEPOSIT = c.uint(2_000_000)

# (tag, name, whether the certificate names a pool, whether it names a DRep, whether it holds a deposit)
CERTIFICATES = [
    (2, "stake delegation", True, False, False),
    (9, "vote delegation", False, True, False),
    (10, "stake and vote delegation", True, True, False),
    (11, "stake registration and delegation", True, False, True),
    (12, "vote registration and delegation", False, True, True),
    (13, "stake and vote registration and delegation", True, True, True),
]

# A place of a hash in a certificate, as the line names it.
PLACES = {
    "credential key hash": ("credential", "key"),
    "credential script hash": ("credential", "script"),
    "pool id": ("pool", None),
    "drep key hash": ("drep", "key"),
    "drep script hash": ("drep", "script"),
}

CASES = []


def hashed(kind, byte, length):
    """`[kind, hash]`: a credential or a DRep whose hash is `length` bytes."""
    return c.array([c.uint({"key": 0, "script": 1}[kind]), c.bstr(c.h(byte, length))])


def certificate(tag, pool, drep, deposit, credential="key", drep_kind="key", at=None, raw=None):
    """The certificate `tag` with key or script hashes, the hash at `at` written as `raw`."""
    def hash_at(place, default):
        return raw if place == at else default

    fields = [c.uint(tag), hash_at("credential", hashed(credential, CREDENTIAL[credential], SIZE))]
    if pool:
        fields.append(hash_at("pool", c.bstr(c.h(POOL, SIZE))))
    if drep:
        fields.append(hash_at("drep", hashed(drep_kind, DREP[drep_kind], SIZE)))
    if deposit:
        fields.append(DEPOSIT)
    return c.array(fields)


def transaction(cert):
    """A whole Conway transaction `[body, {}, true, null]` whose body holds `cert`."""
    return c.array([c.body({4: c.array([cert])}), c.cmap([]), c.TRUE, c.NULL])


def case(name, tag, place, length, raw, hashes=None):
    """`place` is the hash written at `length` bytes; a control names the `hashes` it holds instead."""
    refused = length != SIZE
    CASES.append(
        {
            "id": c.case_id(name),
            "name": name,
            "era": "conway",
            "kind": "transaction",
            "tag": tag,
            "place": place,
            "hashes": hashes,
            "length": length,
            "rule": RULE if refused else None,
            "expect": EXPECT if refused else None,
            "bytes": raw.hex(),
        }
    )


for tag, title, pool, drep, deposit in CERTIFICATES:
    for kinds in ("key", "script"):
        cert = certificate(tag, pool, drep, deposit, credential=kinds, drep_kind=kinds)
        case(f"{title} ({tag}): every hash of {SIZE} bytes, {kinds} hashes", tag, None, SIZE, transaction(cert), kinds)
    for place, (at, kind) in PLACES.items():
        if (at == "pool" and not pool) or (at == "drep" and not drep):
            continue
        lengths = [SIZE - 1, SIZE + 1] + ([2 * SIZE] if at == "pool" else [])
        for length in lengths:
            if at == "pool":
                # the incident's pool id is the 28-byte id written twice
                written = c.bstr(c.h(POOL, length))
            elif at == "credential":
                written = hashed(kind, CREDENTIAL[kind], length)
            else:
                written = hashed(kind, DREP[kind], length)
            cert = certificate(
                tag,
                pool,
                drep,
                deposit,
                credential=kind if at == "credential" else "key",
                drep_kind=kind if at == "drep" else "key",
                at=at,
                raw=written,
            )
            case(f"{title} ({tag}): {place} of {length} bytes", tag, place, length, transaction(cert))


def merge(reference):
    """The corpus lines: each case with the verdict the harness recorded for it (absent fields are null)."""
    assert len(reference) == len(CASES), "the harness answered a different number of cases"
    lines = []
    for case_, verdict in zip(CASES, reference):
        assert (verdict["name"], verdict["era"], verdict["kind"]) == (case_["name"], case_["era"], case_["kind"])
        result = verdict["result"]
        line = dict(case_)
        line.update({"accepted": result["accepted"], "failure": result.get("failure")})
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
