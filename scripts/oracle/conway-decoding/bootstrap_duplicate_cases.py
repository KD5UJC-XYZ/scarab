#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the bootstrap witness duplicates oracle.

Below decoder version 9 the Shelley and Alonzo witness sets read their vkey and bootstrap witnesses
with `Set.fromList <$> decodeList decCBOR`, and from 9 below 12 the Alonzo witness set reads them with
`Set.fromList . NE.toList <$> decodeNonEmptyList decCBOR` (`eras/shelley/impl/src/Cardano/Ledger/Shelley/
TxWits.hs:234-249`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:613-668`, cardano-ledger
f649f975): elements the `Ord` instance calls equal collapse into one. Only from 12 is a duplicate
refused (`decodeNonEmptySetLikeEnforceNoDuplicates`). `Ord BootstrapWitness` compares the
`bootstrapWitKeyHash` alone (`libs/cardano-ledger-core/src/Cardano/Ledger/Keys/Bootstrap.hs:108-109`),
so two bootstrap witnesses with the same address root and different signatures are one; `Ord (WitVKey
kr)` compares the key hash and then the signature's hash (`Keys/WitVKey.hs:58-68`), so two vkey
witnesses of one key with different signatures are two. `validateVerifiedWits`
(`eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Utxow.hs:391-407`) checks the signature of each
witness the sets hold.

The cases, with real Ed25519 signatures of the body (a valid one, and an invalid one: a signature by
the same key of other bytes):

- in each era from Shelley to Conway, at the era's version, a whole transaction whose bootstrap
  witnesses share an address root and differ in their signatures, in each order and among witnesses of
  other roots that sort below and above it, with a witness repeated byte for byte around another of
  its root, with two witnesses of the same root whose chain code and attributes split the same bytes
  differently, and the controls: two witnesses of different roots, the
  same witness twice, and two vkey witnesses of one key with different signatures. The harness reports
  the witnesses each set kept and the verdict of `validateVerifiedWits`;
- in each era, the witness set alone at each decoder version from the era's to 12, with a duplicate
  bootstrap root, the same bootstrap witness twice and a vkey witness of one key signed twice.

The committed corpus is one file, `fixtures/reference/bootstrap-duplicates/cases.jsonl`, a line per
case with its encoding, what it holds and the verdict of the harness, written in two steps:

    python3 scripts/oracle/conway-decoding/bootstrap_duplicate_cases.py --inputs /tmp/bootstrap-duplicates-inputs.json
    docker run --rm --network none --read-only -v /tmp:/fixtures:ro \\
        scarab-conway-decoding:local /fixtures/bootstrap-duplicates-inputs.json > /tmp/bootstrap-duplicates-reference.json
    python3 scripts/oracle/conway-decoding/bootstrap_duplicate_cases.py --inputs /tmp/bootstrap-duplicates-inputs.json \\
        --reference /tmp/bootstrap-duplicates-reference.json --out fixtures/reference/bootstrap-duplicates/cases.jsonl
"""
import argparse
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import cases as c  # noqa: E402  (the CBOR helpers)
import definite_bytes_cases as d  # noqa: E402  (the era bodies)
from devnet import eratx  # noqa: E402  (Ed25519)

ERAS = ["shelley", "allegra", "mary", "alonzo", "babbage", "conway"]
# `eraProtVerLow` of each era (`Cardano/Ledger/Internal/Definition/Era.hs`)
VERSION = {"shelley": 2, "allegra": 3, "mary": 4, "alonzo": 5, "babbage": 7, "conway": 9}
# A duplicate in a set refused from version 12 (`decodeListLikeEnforceNoDuplicates`, ledger-binary
# `Decoding/Decoder.hs:1043-1063`); the Shelley witness set has no such check at any version.
RULE = "does not match the total count that was decoded"
PREFIX = bytes([0x83, 0x00, 0x82, 0x00, 0x58, 0x40])
CHAIN_CODE = bytes([0x92]) * 32
ATTRIBUTES = c.cmap([])
OTHER = b"not the body"
CASES = []


def seed(label):
    return hashlib.sha256(b"scarab bootstrap duplicates " + label.encode()).digest()


def sign_with(secret, message, nonce):
    """An Ed25519 signature of `message` with the nonce derived from `nonce`: a valid signature other
    than the deterministic one (RFC 8032 section 5.1.6 with `r` chosen)."""
    a, _ = eratx._expand(secret)
    public = eratx._compress(eratx._mul(a, eratx._G))
    r = int.from_bytes(hashlib.sha512(b"nonce " + nonce).digest(), "little") % eratx._Q
    big_r = eratx._compress(eratx._mul(r, eratx._G))
    k = int.from_bytes(hashlib.sha512(big_r + public + message).digest(), "little") % eratx._Q
    return big_r + int.to_bytes((r + k * a) % eratx._Q, 32, "little")


def root(vkey, chain_code, attributes):
    """`bootstrapWitKeyHash` (`Bootstrap.hs:112-146`)."""
    return hashlib.blake2b(hashlib.sha3_256(PREFIX + vkey + chain_code + attributes).digest(), digest_size=28).digest()


class Signer:
    def __init__(self, label, chain_code=CHAIN_CODE, attributes=ATTRIBUTES):
        self.label = label
        self.secret = seed(label)
        self.vkey = eratx.public_key(self.secret)
        self.chain_code = chain_code
        self.attributes = attributes
        self.root = root(self.vkey, chain_code, attributes)

    def signature(self, kind, body_hash):
        return {
            "valid": eratx.sign(self.secret, body_hash),
            "valid2": sign_with(self.secret, body_hash, b"second"),
            "invalid": eratx.sign(self.secret, OTHER),
            "invalid2": sign_with(self.secret, OTHER, b"second"),
        }[kind]

    def bootstrap(self, kind, body_hash):
        return c.array([c.bstr(self.vkey), c.bstr(self.signature(kind, body_hash)), c.bstr(self.chain_code),
                        c.bstr(self.attributes)])

    def vkey_witness(self, kind, body_hash):
        return c.array([c.bstr(self.vkey), c.bstr(self.signature(kind, body_hash))])


A = Signer("a")
# the same address root from other fields: the chain code takes the attributes' first byte
A_SPLIT = Signer("a", chain_code=CHAIN_CODE + ATTRIBUTES, attributes=b"")
assert A_SPLIT.root == A.root and A_SPLIT.vkey == A.vkey


def other(below):
    """A signer whose address root sorts below (or above) A's."""
    n = 0
    while True:
        s = Signer(f"other {n}")
        if (s.root < A.root) == below:
            return s
        n += 1


LOW, HIGH = other(True), other(False)
SIGNERS = {"A": A, "Asplit": A_SPLIT, "L": LOW, "H": HIGH}
K = Signer("k")

# Each shape lists the bootstrap witnesses in the order written, as signer:signature.
SHAPES = [
    ["A:valid", "A:invalid"],
    ["A:invalid", "A:valid"],
    ["A:invalid", "A:invalid2"],
    ["A:valid", "A:valid2"],
    ["A:valid", "A:valid"],
    ["A:invalid", "A:invalid"],
    ["L:valid", "A:invalid", "A:valid"],
    ["L:valid", "A:valid", "A:invalid"],
    ["A:invalid", "A:valid", "H:valid"],
    ["A:valid", "A:invalid", "H:valid"],
    ["A:invalid", "L:valid", "A:valid"],
    ["A:valid", "L:valid", "A:invalid"],
    ["A:invalid", "H:valid", "A:valid"],
    ["A:valid", "H:valid", "A:invalid"],
    ["A:invalid", "A:invalid2", "A:valid"],
    ["A:valid", "A:invalid", "A:invalid2"],
    ["A:invalid", "A:valid", "A:invalid2"],
    ["A:valid", "A:invalid", "A:valid2"],
    ["A:invalid", "A:valid", "A:invalid"],
    ["A:valid", "A:invalid", "A:valid"],
    ["A:invalid", "Asplit:valid"],
    ["Asplit:invalid", "A:valid"],
    ["A:valid", "H:valid"],
    ["A:valid", "H:invalid"],
]
# vkey witnesses of one key (the control): the reference's `Ord` tells their signatures apart
VKEY_SHAPES = [
    ["K:valid", "K:invalid"],
    ["K:invalid", "K:valid"],
    ["K:valid", "K:valid2"],
    ["K:valid", "K:valid"],
]
SWEEP = [["A:invalid", "A:valid"], ["A:valid", "A:valid"]]
SWEEP_VKEYS = [["K:valid", "K:invalid"]]


def witness_set(era, bootstraps, vkeys):
    body_hash = hashlib.blake2b(d.body(era), digest_size=32).digest()
    fields = []
    if vkeys:
        fields.append((c.uint(0), c.array([K.vkey_witness(w.split(":")[1], body_hash) for w in vkeys])))
    if bootstraps:
        fields.append((c.uint(2), c.array([SIGNERS[w.split(":")[0]].bootstrap(w.split(":")[1], body_hash)
                                           for w in bootstraps])))
    return c.cmap(fields)


def transaction(era, wits):
    body = d.body(era)
    return c.array([body, wits, c.NULL] if VERSION[era] < VERSION["alonzo"] else [body, wits, c.TRUE, c.NULL])


def case(name, era, kind, version, bootstraps, vkeys, raw):
    alonzo = VERSION[era] >= VERSION["alonzo"]
    roots = [SIGNERS[w.split(":")[0]].root for w in bootstraps]
    duplicated = len(set(roots)) < len(roots) or len(set(vkeys)) < len(vkeys)
    CASES.append({
        "id": c.case_id(name),
        "name": name,
        "era": era,
        "kind": kind,
        "version": version,
        "bootstraps": bootstraps,
        "vkeys": vkeys,
        "rule": RULE if alonzo and version >= 12 and duplicated else None,
        "bytes": raw.hex(),
    })


for era in ERAS:
    for shape in SHAPES:
        raw = transaction(era, witness_set(era, shape, []))
        case(f"{era} transaction: bootstrap witnesses {', '.join(shape)}", era, "transaction", VERSION[era], shape, [],
             raw)
    for shape in VKEY_SHAPES:
        raw = transaction(era, witness_set(era, [], shape))
        case(f"{era} transaction: vkey witnesses {', '.join(shape)}", era, "transaction", VERSION[era], [], shape, raw)

for era in ERAS:
    for version in range(VERSION[era], 13):
        for shape in SWEEP:
            case(f"{era} witnesses at version {version}: bootstrap witnesses {', '.join(shape)}", era, "witnesses",
                 version, shape, [], witness_set(era, shape, []))
        for shape in SWEEP_VKEYS:
            case(f"{era} witnesses at version {version}: vkey witnesses {', '.join(shape)}", era, "witnesses", version,
                 [], shape, witness_set(era, [], shape))


def harness_input(entry):
    """What the harness reads: the era's witness set or transaction, at the case's version."""
    x = {"name": entry["name"], "era": entry["era"], "kind": entry["kind"], "bytes": entry["bytes"], "ids": True,
         "bootstraps": True, "kept": True, "decoderVersion": entry["version"]}
    if entry["kind"] == "transaction":
        x["verify"] = True
    return x


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
                "keyHashes": result.get("bootstrap"),
                "keptBootstraps": result.get("keptBootstraps"),
                "keptVkeys": result.get("keptVkeys"),
                "verified": result.get("verified"),
                "invalidKeys": result.get("invalidKeys"),
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
