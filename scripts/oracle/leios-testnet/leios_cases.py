#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the Leios testnet block oracle (#711).

The reference is the build the public Leios testnet runs: cardano-node b004fe0c (release
rc-2026w44), with cardano-ledger 9df9f98c and ouroboros-consensus 2416493b. A block as BlockFetch
carries it is `[tag, block]` (`decodeDiskHfcBlock`, consensus `Cardano/Node.hs:174-199`, a definite
list read by `enforceSize`); tag 8 is a Dijkstra block, `decodeRecordNamed "Block" (const 2)` of the
Leios header and the body (ledger `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Block.hs:34-42`),
the body `decodeRecordNamed "DijkstraBlockBodyRaw" (const 3)` of the transactions (`decodeSeq`), a
Leios certificate or null and a Peras certificate or null (`BlockBody/Internal.hs:191-208`), each
transaction `decodeRecordNamed "DijkstraTx" (const 4)` (`Tx.hs:175-182`). The Leios header and its
body are `decodeRecordNamed` too (`libs/cardano-protocol/src/Cardano/Protocol/Leios/BlockHeader.hs`),
and the `RecD` records a transaction can hold are read by `decodeRecordNamed` from decoder version 12
(`ifDecoderVersionAtLeast (natVersion @12)`, e.g. `Mary/Value.hs:308-321`). So every list of a
Leios block is definite or indefinite with its fields before the break, and none is read detached:
an empty indefinite list `9f ff` with the fields after it is refused.

The cases are the blocks of the testnet captured in `fixtures/leios/testnet/` (the current network,
`blocks-rc-2026w44.tsv`, and the previous one, `blocks.tsv`), those blocks with one list written in
another form, a block of synthetic transactions whose output value is written in each form, and Leios
headers alone in the forms of the header decoding oracle.

    python3 scripts/oracle/leios-testnet/leios_cases.py --inputs /tmp/leios-inputs.json
    docker run --rm --network none --read-only -v /tmp:/fixtures:ro \\
        scarab-leios-testnet:local /fixtures/leios-inputs.json > /tmp/leios-reference.json
    python3 scripts/oracle/leios-testnet/leios_cases.py --inputs /tmp/leios-inputs.json \\
        --reference /tmp/leios-reference.json --out fixtures/reference/leios-testnet-blocks/cases.jsonl
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..", "..")
sys.path.insert(0, os.path.join(HERE, "..", "conway-decoding"))
sys.path.insert(0, os.path.join(HERE, "..", "header-decoding"))
import cases as c  # noqa: E402  (the CBOR helpers)
import header_cases as hc  # noqa: E402  (the header fields and forms)
import leios_tx_cases as tc  # noqa: E402  (the transaction cases, #750)

CASES = []


def case(kind, name, raw, source):
    CASES.append({"id": c.case_id(name), "name": name, "kind": kind, "source": source, "bytes": raw.hex()})

def end_of(b, i):
    """The index after the CBOR item that starts at `i`. An explicit stack of the items still to
    read in each open collection (None for an indefinite one), so nesting depth costs no recursion."""
    pending = [1]
    while pending:
        if pending[-1] == 0:
            pending.pop()
            continue
        if pending[-1] is None and b[i] == 0xFF:
            pending.pop()
            i += 1
            continue
        if pending[-1] is not None:
            pending[-1] -= 1
        ib = b[i]
        major, ai = ib >> 5, ib & 31
        i += 1
        if ai < 24:
            arg = ai
        elif ai in (24, 25, 26, 27):
            width = 1 << (ai - 24)
            arg = int.from_bytes(b[i:i + width], "big")
            i += width
        elif ai == 31:
            arg = None
        else:
            raise ValueError(f"reserved head {ib:#x}")
        if major in (2, 3):
            if arg is None:
                pending.append(None)
            else:
                i += arg
        elif major == 4:
            pending.append(arg)
        elif major == 5:
            pending.append(None if arg is None else 2 * arg)
        elif major == 6:
            pending.append(1)
    return i


def parts(b):
    """The items of a definite list, as their original bytes."""
    ib = b[0]
    assert ib >> 5 == 4 and ib & 31 != 31, "a definite list"
    i = 1 if ib & 31 < 24 else 1 + (1 << ((ib & 31) - 24))
    out = []
    while i < len(b):
        j = end_of(b, i)
        out.append(b[i:j])
        i = j
    return out


def content(b):
    """The bytes of a definite byte string."""
    ai = b[0] & 31
    return b[1 if ai < 24 else 1 + (1 << (ai - 24)):]


def items(fields, form="definite"):
    return hc.items(fields, form)


def load(path):
    rows = {}
    with open(os.path.join(ROOT, path)) as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            cols = line.rstrip("\n").split("\t")
            rows[cols[0]] = bytes.fromhex(cols[-1])
    return rows


CURRENT = "fixtures/leios/testnet/blocks-rc-2026w44.tsv"
PREVIOUS = "fixtures/leios/testnet/blocks.tsv"
current = load(CURRENT)
previous = load(PREVIOUS)
FORMS_BLOCK = "scripts/oracle/header-decoding/header_cases.py"


class Block:
    """A tag-8 block split into its parts: the header, its body and signature, and the block body's
    transactions and certificates."""

    def __init__(self, raw):
        envelope = parts(raw)
        assert envelope[0] == c.uint(8)
        self.header, self.body = parts(envelope[1])
        self.header_body, self.signature = parts(self.header)
        self.txs, self.leios, self.peras = parts(self.body)

    def with_body(self, txs=None, leios=None, peras=None, form="definite", tx_form="definite"):
        tx_items = parts(self.txs) if txs is None else txs
        return items(
            [items(tx_items, tx_form), self.leios if leios is None else leios, self.peras if peras is None else peras],
            form,
        )

    def build(self, header=None, body=None, form="definite"):
        return c.array([c.uint(8), items([header or self.header, body or self.body], form)])


def block_case(name, raw, source):
    case("block", name, raw, source)


for label, raw in current.items():
    block_case(f"rc-2026w44 {label}", raw, f"{CURRENT} {label}")
for label, raw in previous.items():
    block_case(f"previous network {label}", raw, f"{PREVIOUS} {label}")

# The forms of each list of a block, on the block of the previous network with transactions.
SOURCE = f"{PREVIOUS} dijkstra-with-txs"
base = Block(previous["dijkstra-with-txs"])
block_case("block indefinite", base.build(form="indefinite"), SOURCE)
block_case("block detached", b"\x82\x08\x9f\xff" + base.header + base.body, SOURCE)
block_case("block of three items", c.array([c.uint(8), c.array([base.header, base.body, c.NULL])]), SOURCE)
block_case("block of one item", c.array([c.uint(8), c.array([base.header])]), SOURCE)
block_case("envelope indefinite", b"\x9f\x08" + items([base.header, base.body]) + b"\xff", SOURCE)
block_case("envelope of three items", c.array([c.uint(8), items([base.header, base.body]), c.NULL]), SOURCE)
block_case("envelope tag 9", c.array([c.uint(9), items([base.header, base.body])]), SOURCE)
block_case("trailing byte after the block", base.build() + c.NULL, SOURCE)
block_case("block with its header indefinite", base.build(header=items([base.header_body, base.signature], "indefinite")), SOURCE)
block_case("block with its header detached", base.build(header=items([base.header_body, base.signature], "detached")), SOURCE)
block_case("block with its header body indefinite",
           base.build(header=items([items(parts(base.header_body), "indefinite"), base.signature])), SOURCE)
block_case("block with its header body detached",
           base.build(header=items([items(parts(base.header_body), "detached"), base.signature])), SOURCE)
block_case("block body indefinite", base.build(body=base.with_body(form="indefinite")), SOURCE)
block_case("block body detached", base.build(body=base.with_body(form="detached")), SOURCE)
block_case("block body of two items", base.build(body=items(parts(base.body)[:2])), SOURCE)
block_case("block body of four items", base.build(body=items(parts(base.body) + [c.NULL])), SOURCE)
block_case("transaction list indefinite", base.build(body=base.with_body(tx_form="indefinite")), SOURCE)
block_case("transaction list tagged 258", base.build(body=items([c.tag(258, base.txs), base.leios, base.peras])),
           SOURCE)
first, *rest = parts(base.txs)
tx_parts = parts(first)
block_case("transaction indefinite", base.build(body=base.with_body(txs=[items(tx_parts, "indefinite")] + rest)),
           SOURCE)
block_case("transaction detached", base.build(body=base.with_body(txs=[items(tx_parts, "detached")] + rest)), SOURCE)
block_case("transaction of three items", base.build(body=base.with_body(txs=[items(tx_parts[:3])] + rest)), SOURCE)
block_case("transaction of five items",
           base.build(body=base.with_body(txs=[items(tx_parts + [c.TRUE])] + rest)), SOURCE)
block_case("transaction marked invalid",
           base.build(body=base.with_body(txs=[items(tx_parts[:3] + [b"\xf4"])] + rest)), SOURCE)
block_case("transaction validity an integer",
           base.build(body=base.with_body(txs=[items(tx_parts[:3] + [c.uint(1)])] + rest)), SOURCE)
block_case("peras certificate bytes", base.build(body=base.with_body(peras=c.bstr(c.h(0x5A, 16)))), SOURCE)
block_case("peras certificate chunked bytes",
           base.build(body=base.with_body(peras=b"\x5f" + c.bstr(c.h(0x5A, 8)) + c.bstr(c.h(0x5B, 8)) + b"\xff")),
           SOURCE)
block_case("peras certificate an integer", base.build(body=base.with_body(peras=c.uint(7))), SOURCE)

# The forms of the Leios certificate, on the certifying block of the previous network.
SOURCE = f"{PREVIOUS} dijkstra-certificate"
cert_block = Block(previous["dijkstra-certificate"])
empty_header = Block(current["block-0"]).header
signers, signature = parts(cert_block.leios)
for form in ["indefinite", "detached"]:
    block_case(f"leios certificate {form}",
               cert_block.build(body=cert_block.with_body(leios=items([signers, signature], form))), SOURCE)
block_case("leios certificate signature chunked",
           cert_block.build(body=cert_block.with_body(
               leios=c.array([signers, b"\x5f" + c.bstr(content(signature)[:24]) + c.bstr(content(signature)[24:])
                              + b"\xff"]))),
           SOURCE)
block_case("leios certificate signers with a wide length head",
           cert_block.build(body=cert_block.with_body(leios=c.array([hc.nbstr(content(signers), 4), signature]))),
           SOURCE)
block_case("leios certificate of 8193 signer bytes",
           cert_block.build(body=cert_block.with_body(leios=c.array([c.bstr(bytes(8193)), signature]))), SOURCE)
block_case("leios certificate of 8192 signer bytes",
           cert_block.build(body=cert_block.with_body(leios=c.array([c.bstr(bytes(8192)), signature]))), SOURCE)

# The certificate's signature: the reference reads it with the BLS12-381 MinSig signature decoder
# of cardano-base c1e1ed8d. Off the curve and outside the subgroup are the compressed G1 vectors of
# `fixtures/leios/bls/cardano-base-serde-test-vectors` (lines 2 and 3); the identity is the
# compressed point at infinity.
SERDE = [bytes.fromhex(line.strip()) for line in open(os.path.join(ROOT, "fixtures/leios/bls/cardano-base-serde-test-vectors"))]
for label, sig in [("47 bytes", content(signature)[:47]), ("49 bytes", content(signature) + b"\x00"),
                   ("off the curve", SERDE[1]), ("outside the subgroup", SERDE[2]),
                   ("the identity", b"\xc0" + bytes(47)), ("not compressed", b"\x00" + content(signature)[1:])]:
    block_case(f"leios certificate signature {label}",
               cert_block.build(body=cert_block.with_body(leios=c.array([signers, c.bstr(sig)]))), SOURCE)

# A block of tag 7: in this build a Conway block, `ShelleyBlock (Praos c) ConwayEra`, read at decoder
# version 9 with the Praos header (`Cardano/Block.hs:269-278`): a definite header and an empty body.
CONWAY = "conway"
block_case("tag 7 Conway block with a Praos header",
           c.array([c.uint(7), hc.block(CONWAY, hc.header(CONWAY))]), FORMS_BLOCK)
block_case("tag 7 Conway block with a Leios header",
           c.array([c.uint(7), hc.block(CONWAY, empty_header)]), f"{CURRENT} block-0")

# Synthetic transactions in the empty block 0 of the current network: an output whose value is the
# `MaryValue` record, in each form and in each output form.
SOURCE = f"{CURRENT} block-0"
empty = Block(current["block-0"])
ADDRESS = c.bstr(b"\x60" + c.h(0x22, 28))
ASSETS = c.cmap([(c.bstr(c.h(0x88, 28)), c.cmap([(c.bstr(b"a"), c.uint(5))]))])


def value(form):
    return items([c.uint(2_000_000), ASSETS], form)


def tx(output, valid=c.TRUE):
    body = c.cmap([(c.uint(0), c.array([c.array([c.bstr(c.h(0x11, 32)), c.uint(0)])])),
                   (c.uint(1), c.array([output])),
                   (c.uint(2), c.uint(200_000))])
    return items([body, c.cmap([]), c.NULL, valid])


for form in ["definite", "indefinite", "detached"]:
    block_case(f"transaction output list with its value {form}",
               empty.build(body=empty.with_body(txs=[tx(c.array([ADDRESS, value(form)]))])), SOURCE)
    block_case(f"transaction output map with its value {form}",
               empty.build(body=empty.with_body(
                   txs=[tx(c.cmap([(c.uint(0), ADDRESS), (c.uint(1), value(form))]))])), SOURCE)
block_case("transaction output with a coin value",
           empty.build(body=empty.with_body(txs=[tx(c.array([ADDRESS, c.uint(2_000_000)]))])), SOURCE)
block_case("transaction of a zero body and zero witnesses",
           empty.build(body=empty.with_body(txs=[c.array([c.uint(0), c.uint(0), c.NULL, c.TRUE])])), SOURCE)
block_case("two transactions, the second with its value detached",
           empty.build(body=empty.with_body(txs=[tx(c.array([ADDRESS, value("definite")])),
                                                 tx(c.array([ADDRESS, value("detached")]))])), SOURCE)

# Leios headers alone, as ChainSync carries them inside their era envelope: the real header of
# block 0 of the current network, and the forms of the header decoding oracle (header_cases.py).
case("header", "rc-2026w44 block-0 header", empty.header, f"{CURRENT} block-0")
L = "leios"
FORMS = "scripts/oracle/header-decoding/header_cases.py"


def header(name, raw):
    case("header", f"header {name}", raw, FORMS)


header("definite", hc.header(L))
header("indefinite", hc.header(L, header_form="indefinite"))
header("detached", hc.header(L, header_form="detached"))
header("body indefinite", hc.header(L, body_form="indefinite"))
header("body detached", hc.header(L, body_form="detached"))
header("both indefinite", hc.header(L, body_form="indefinite", header_form="indefinite"))
header("both detached", hc.header(L, body_form="detached", header_form="detached"))
header("non-minimal heads", hc.header(L, nonmin=True, announcement=("definite", 5000)))
header("vrf certificate indefinite", hc.header(L, vrf="indefinite"))
header("issuer key chunked", hc.header(L, chunked=True))
header("version info major at the last protocol version", hc.header(L, major=13))
header("version info major above the last protocol version", hc.header(L, major=14))
header("version info major over a word32", hc.header(L, major=2**32))
header("version info software tag over a word32", hc.header(L, minor=2**32))
header("version info indefinite", hc.header(L, pv="indefinite"))
header("version info detached", hc.header(L, pv="detached"))
header("announcement", hc.header(L, announcement=("definite", 5000)))
header("announcement indefinite", hc.header(L, announcement=("indefinite", 5000)))
header("announcement detached", hc.header(L, announcement=("detached", 5000)))
header("announcement size over a word32", hc.header(L, announcement=("definite", 2**32)))
header("operational certificate indefinite", hc.header(L, ocert="indefinite"))
header("operational certificate detached", hc.header(L, ocert="detached"))
header("body size over a word32", hc.header(L, size=2**32))
header("word64 values at and above 2^63", hc.header(L, large=True))
header("vrf output empty", hc.header(L, output=0))
header("detached without its signature", b"\x9f\xff" + items(hc.fields(L)))
header("body detached one field short", c.array([b"\x9f\xff" + b"".join(hc.fields(L)[:-1]), hc.SIGNATURE]))
header("body of eleven fields", c.array([items(hc.fields(L)[:-1]), hc.SIGNATURE]))
header("every list indefinite but the vrf certificate",
       hc.header(L, body_form="indefinite", header_form="indefinite", ocert="indefinite", pv="indefinite",
                 announcement=("indefinite", 5000)))


def transactions(paths):
    """The transaction cases (`leios_tx_cases.py`): each transaction of every tag-8 block with
    transactions in the captures of both networks and in `paths` (block captures in the same TSV form
    that this branch does not hold yet), then the synthetic ones."""
    blocks = []
    seen = set()
    for path in [PREVIOUS, CURRENT] + list(paths):
        rows = load(path) if not os.path.isabs(path) else load_abs(path)
        label = os.path.basename(path)
        for name, raw in rows.items():
            if parts(raw)[0] != c.uint(8):
                continue
            txs = parts(Block(raw).txs) if Block(raw).txs[0] != 0x9F else indefinite_parts(Block(raw).txs)
            if txs and (label, name) not in seen:
                seen.add((label, name))
                source = f"fixtures/leios/testnet/{label} {name}"
                blocks.append((source, txs))
    tc.build(lambda name, raw, source: case("tx", name, raw, source), blocks)


def load_abs(path):
    rows = {}
    with open(path) as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            cols = line.rstrip("\n").split("\t")
            rows[cols[0]] = bytes.fromhex(cols[-1])
    return rows


def indefinite_parts(b):
    """The items of an indefinite list, as their original bytes."""
    assert b[0] == 0x9F and b[-1] == 0xFF
    out, i = [], 1
    while i < len(b) - 1:
        j = end_of(b, i)
        out.append(b[i:j])
        i = j
    return out


def merge(reference):
    """The corpus lines: each case with the verdict and what the reference read from it."""
    assert len(reference) == len(CASES), "the harness answered a different number of cases"
    lines = []
    keys = ["hash", "headersize", "signable", "blockno", "slot", "declaredbodysize", "declaredbodyhash",
            "versioninfo", "containscert", "announcement", "tag", "bodyhash", "bodysize", "txs", "leioscert",
            "perascert", "valid", "scripts", "datums", "redeemers", "subtxs"]
    for case_, verdict in zip(CASES, reference):
        assert (verdict["name"], verdict["kind"]) == (case_["name"], case_["kind"])
        result = verdict["result"]
        line = {
            "id": case_["id"],
            "name": case_["name"],
            "kind": case_["kind"],
            "source": case_["source"],
            "accepted": result["accepted"],
            "failure": result.get("failure"),
        }
        line.update({k: result[k] for k in keys if k in result})
        if case_["kind"] == "tx" and result["accepted"]:
            line["txid"] = result["id"]
        line["bytes"] = case_["bytes"]
        lines.append(json.dumps({k: v for k, v in line.items() if v is not None or k == "announcement"},
                                separators=(",", ":")))
    return lines


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", help="write the array of original encodings the harness reads")
    parser.add_argument("--reference", help="the harness output for those inputs, to merge")
    parser.add_argument("--out", help="write the corpus lines (needs --reference)")
    parser.add_argument("--blocks", action="append", default=[],
                        help="a further block capture (TSV) whose transactions become cases")
    args = parser.parse_args()
    transactions(args.blocks)
    names = [x["name"] for x in CASES]
    assert len(names) == len(set(names)), "case names must be unique"
    ids = [x["id"] for x in CASES]
    assert len(ids) == len(set(ids)), "case ids must be unique"
    if args.inputs:
        with open(args.inputs, "w") as f:
            json.dump([{k: x[k] for k in ("name", "kind", "bytes")} for x in CASES], f, indent=1)
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
