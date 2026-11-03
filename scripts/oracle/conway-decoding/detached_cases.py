#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the detached `RecD` record oracle (#687).

Below decoder version 12 the reference reads a `RecD` record's list head with `decodeRecordNamed`
around a decoder that reads nothing (`decodeCount (RecD cn) n`,
`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Coders.hs:482`, cardano-ledger
f649f975). An indefinite list's break is checked at once (`decodeListLikeT`,
`Decoding/Decoder.hs:690-708`), so an empty indefinite list `9f ff` passes and the record's fields
are read from the items after the break: the record is "detached" from its fields, and every
enclosing decoder counts it as one item. Each case here writes a record so, in each place a block or
a transaction holds one, era by era:
- the update proposal `Update` (`Shelley/PParams.hs:267`), Shelley to Babbage, and the prices and
  execution units of a parameter update (`Plutus/ExUnits.hs:195-231`), Alonzo to Conway;
- the value of an output `MaryValue` (`Mary/Value.hs:286-306`), Mary to Conway, in each output form
  and in a collateral return;
- the execution units of a redeemer, Alonzo to Conway, list and map forms;
- Conway's `GovActionId`, `VotingProcedure`, `ProposalProcedure`, `Constitution` and `Anchor`
  (`Conway/Governance/Procedures.hs`, `BaseTypes.hs:996-1004`);
- the list form of auxiliary data (`Allegra/TxAuxData.hs:185-210`, `Alonzo/TxAuxData.hs:215-262`);
- a whole transaction, and the parts of a block after its header with the records spilling inside
  indefinite lists of bodies and witness sets;
- the set of `GovActionId` that the node-to-client query `GetProposals` reads (#700);
and the refusals next to them: fields that run past the end of the input, lists and maps whose
counts do not fit the records, a record of the wrong length, and empty indefinite lists in places
that are not records.

Every case is read with `ids` set, so the harness reports, for an accepted body, transaction or block
body, the ids it hashes from the bytes it read. The committed corpus is
`fixtures/reference/recd-detached/cases.jsonl`, written in two steps:

    python3 scripts/oracle/conway-decoding/detached_cases.py --inputs /tmp/detached-inputs.json
    docker run --rm --network none --read-only -v /tmp:/fixtures:ro \\
        scarab-conway-decoding:local /fixtures/detached-inputs.json > /tmp/detached-reference.json
    python3 scripts/oracle/conway-decoding/detached_cases.py --inputs /tmp/detached-inputs.json \\
        --reference /tmp/detached-reference.json --out fixtures/reference/recd-detached/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402  (the CBOR helpers)
import definite_bytes_cases as d  # noqa: E402  (the era bodies)
import pre_conway_cases as p  # noqa: E402  (the genesis key of an update)

ERAS = ["shelley", "allegra", "mary", "alonzo", "babbage", "conway"]
CASES = []


def since(era, first):
    return ERAS.index(era) >= ERAS.index(first)


def detached(fields):
    """A record written as an empty indefinite list with its fields after the break."""
    return b"\x9f\xff" + b"".join(fields)


def case(era, kind, name, raw):
    name = f"{era} {kind} {name}"
    CASES.append({"id": c.case_id(name), "name": name, "era": era, "kind": kind, "bytes": raw.hex(),
                  "ids": True})


ADDRESS = c.bstr(b"\x61" + c.h(0x22, 28))
MULTIASSET = c.cmap([(c.bstr(c.h(0x88, 28)), c.cmap([(c.bstr(b"a"), c.uint(1))]))])
VALUE = detached([c.uint(2_000_000), MULTIASSET])
DATUM_HASH = c.bstr(c.h(0x58, 32))
SCRIPT_REF = c.tag(24, c.bstr(c.array([c.uint(0), c.array([c.uint(0), c.bstr(c.KEY_HASH)])])))
UNITS = detached([c.uint(1), c.uint(2)])
PRICES = detached([c.tag(30, c.array([c.uint(1), c.uint(2)]))] * 2)
URL = c.text("https://example.invalid/a")
DATA_HASH = c.bstr(c.h(0x44, 32))
ANCHOR = detached([URL, DATA_HASH])
DEFINITE_ANCHOR = c.array([URL, DATA_HASH])
TX_ID = c.bstr(c.h(0x66, 32))
ACTION_ID = detached([TX_ID, c.uint(0)])
ACCOUNT = c.bstr(b"\xe1" + c.h(0x55, 28))
CRED = c.array([c.uint(0), c.bstr(c.KEY_HASH)])
COLD = c.array([c.uint(0), c.bstr(c.h(0x73, 28))])
VOTER = c.array([c.uint(2), c.bstr(c.KEY_HASH)])
AUX = detached([c.cmap([(c.uint(674), c.text("x"))]), c.array([])])


def body(era, extra):
    return d.body(era, extra)


def outputs(*items, indef=False):
    return {1: c.array(list(items), indef=indef)}


def update(proposed):
    return {6: detached([c.cmap([(p.GENESIS, proposed)]), c.uint(10)])}


def proposals(*items):
    return {20: c.array(list(items))}


def proposal(action, anchor=ANCHOR):
    return detached([c.uint(1_000_000), ACCOUNT, action, anchor])


def votes(action_id, procedure):
    return {19: c.cmap([(VOTER, c.cmap([(action_id, procedure)]))])}


# --- the update proposal and the records of a parameter update -----------------------------------
for era in ERAS[:5]:
    case(era, "body", "update", body(era, update(c.cmap([(c.uint(0), c.uint(44))]))))
    # the epoch is missing: the update's last field is read from the end of the body
    # the epoch is missing: the update's last field is read from the end of the body
    case(era, "body", "update without its epoch",
         body(era, {6: detached([c.cmap([(p.GENESIS, c.cmap([(c.uint(0), c.uint(44))]))])])}))
    if since(era, "alonzo"):
        for key, label, record in [(19, "prices", PRICES), (20, "transaction units", UNITS),
                                   (21, "block units", UNITS)]:
            case(era, "body", f"update {label}",
                 body(era, {6: c.array([c.cmap([(p.GENESIS, c.cmap([(c.uint(key), record)]))]), c.uint(10)])}))
        case(era, "body", "update and its units",
             body(era, update(c.cmap([(c.uint(20), UNITS), (c.uint(21), UNITS)]))))

# --- the value of an output ---------------------------------------------------------------------
for era in ERAS[2:]:
    case(era, "body", "value", body(era, outputs(c.array([ADDRESS, VALUE]))))
    case(era, "body", "value in an indefinite output list", body(era, outputs(c.array([ADDRESS, VALUE]), indef=True)))
    case(era, "body", "value in an indefinite output", body(era, outputs(c.array([ADDRESS, VALUE], indef=True))))
    case(era, "body", "value of the second output",
         body(era, outputs(c.OUTPUT, c.array([ADDRESS, VALUE]))))
    # the output counts the record's fields as its own items: `wrong number of terms`
    case(era, "body", "value in an output that counts its fields", body(era, {1: c.array([b"\x84" + ADDRESS + VALUE])}))
    # the outputs count the record's fields as outputs
    case(era, "body", "value in outputs that count its fields",
         body(era, {1: b"\x83" + c.array([ADDRESS, VALUE])}))
    # the multi-asset is missing: the next key of the body is read as it
    case(era, "body", "value without its multi-asset",
         body(era, outputs(c.array([ADDRESS, detached([c.uint(2_000_000)])]))))
    if since(era, "alonzo"):
        case(era, "body", "value with a datum hash", body(era, outputs(c.array([ADDRESS, VALUE, DATUM_HASH]))))
        case(era, "body", "value with a datum hash in an indefinite output",
             body(era, outputs(c.array([ADDRESS, VALUE, DATUM_HASH], indef=True))))
    if since(era, "babbage"):
        case(era, "body", "value in a map output",
             body(era, outputs(c.cmap([(c.uint(0), ADDRESS), (c.uint(1), VALUE)]))))
        case(era, "body", "value in a map output with a reference script",
             body(era, outputs(c.cmap([(c.uint(0), ADDRESS), (c.uint(1), VALUE), (c.uint(3), SCRIPT_REF)]))))
        case(era, "body", "value in an indefinite map output",
             body(era, outputs(c.cmap([(c.uint(0), ADDRESS), (c.uint(1), VALUE)], indef=True))))
        case(era, "body", "value of a collateral return", body(era, {16: c.array([ADDRESS, VALUE])}))
    else:
        # a map output is not read before Babbage, detached value or not
        case(era, "body", "value in a map output",
             body(era, outputs(c.cmap([(c.uint(0), ADDRESS), (c.uint(1), VALUE)]))))
# no value is a record before Mary
for era in ERAS[:2]:
    case(era, "body", "value", body(era, outputs(c.array([ADDRESS, VALUE]))))


# --- the execution units of a redeemer -----------------------------------------------------------
def witness_set(key, value):
    return d.wits(key, value)


for era in ERAS[3:]:
    case(era, "witnesses", "redeemer units",
         witness_set(5, c.array([c.array([c.uint(0), c.uint(0), c.uint(0), UNITS])])))
    case(era, "witnesses", "redeemer units in an indefinite redeemer",
         witness_set(5, c.array([c.array([c.uint(0), c.uint(0), c.uint(0), UNITS], indef=True)])))
    case(era, "witnesses", "redeemer units in an indefinite redeemer list",
         witness_set(5, c.array([c.array([c.uint(0), c.uint(0), c.uint(0), UNITS])], indef=True)))
    case(era, "witnesses", "redeemer units and a vkey witness after them",
         c.cmap([(c.uint(5), c.array([c.array([c.uint(0), c.uint(0), c.uint(0), UNITS])])),
                 (c.uint(0), c.array([c.vkey_witness()]))]))
    # the redeemer counts the units' fields as its own items: a record of the wrong length
    case(era, "witnesses", "redeemer units in a redeemer that counts their fields",
         witness_set(5, c.array([b"\x86" + c.uint(0) + c.uint(0) + c.uint(0) + UNITS])))
    # the units' steps are missing: the end of the input
    case(era, "witnesses", "redeemer units without their steps",
         witness_set(5, c.array([c.array([c.uint(0), c.uint(0), c.uint(0), detached([c.uint(1)])])])))
    # the witness set counts one entry more: the units' fields are read as its entries
    case(era, "witnesses", "redeemer units in a witness set that counts their fields",
         b"\xa2" + c.uint(5) + c.array([c.array([c.uint(0), c.uint(0), c.uint(0), UNITS])]))
    if era == "conway":
        case(era, "witnesses", "redeemer units in the map form",
             witness_set(5, c.cmap([(c.array([c.uint(0), c.uint(0)]), c.array([c.uint(0), UNITS]))])))
        case(era, "witnesses", "redeemer units in an indefinite map form",
             witness_set(5, c.cmap([(c.array([c.uint(0), c.uint(0)]), c.array([c.uint(0), UNITS]))], indef=True)))
# the redeemers are not a record: an empty indefinite list there is the empty list, and the items
# after it are read by the witness set
for era in ERAS[3:]:
    case(era, "witnesses", "empty indefinite redeemer list and items after it",
         b"\xa1" + c.uint(5) + detached([c.uint(1), c.uint(2)]))
# the vkey witnesses are not a record either
case("conway", "witnesses", "empty indefinite vkey witnesses and items after them",
     b"\xa1" + c.uint(0) + detached([c.vkey_witness()]))


# --- Conway governance --------------------------------------------------------------------------
def conway(name, extra):
    case("conway", "body", name, body("conway", extra))


INFO = c.array([c.uint(6)])
conway("proposal", proposals(proposal(INFO)))
conway("proposal with a definite anchor", proposals(proposal(INFO, anchor=DEFINITE_ANCHOR)))
conway("proposal anchor in a definite proposal",
       proposals(c.array([c.uint(1_000_000), ACCOUNT, INFO, ANCHOR])))
conway("two proposals", proposals(proposal(INFO), proposal(c.array([c.uint(3), c.NULL]))))
conway("proposals in a tagged set", {20: c.tag(258, c.array([proposal(INFO)]))})
conway("proposal in an indefinite list", {20: c.array([proposal(INFO)], indef=True)})
conway("constitution", proposals(proposal(c.array([c.uint(5), c.NULL, detached([ANCHOR, c.NULL])]))))
conway("constitution with a script hash",
       proposals(proposal(c.array([c.uint(5), c.NULL, detached([DEFINITE_ANCHOR, c.bstr(c.h(0x77, 28))])]))))
for label, action in {
    "parameter change": c.array([c.uint(0), ACTION_ID, c.cmap([(c.uint(0), c.uint(44))]), c.NULL]),
    "hard fork": c.array([c.uint(1), ACTION_ID, c.array([c.uint(10), c.uint(0)])]),
    "no confidence": c.array([c.uint(3), ACTION_ID]),
    "committee update": c.array([c.uint(4), ACTION_ID, c.array([]), c.cmap([]),
                                 c.tag(30, c.array([c.uint(1), c.uint(2)]))]),
    "new constitution": c.array([c.uint(5), ACTION_ID, c.array([DEFINITE_ANCHOR, c.NULL])]),
}.items():
    conway(f"{label} previous action id", proposals(proposal(action)))
for key, label, record in [(19, "prices", PRICES), (20, "transaction units", UNITS), (21, "block units", UNITS)]:
    conway(f"parameter change {label}",
           proposals(proposal(c.array([c.uint(0), c.NULL, c.cmap([(c.uint(key), record)]), c.NULL]))))
conway("vote action id", votes(ACTION_ID, c.array([c.uint(1), c.NULL])))
conway("voting procedure", votes(c.array([TX_ID, c.uint(0)]), detached([c.uint(1), c.NULL])))
conway("vote anchor", votes(c.array([TX_ID, c.uint(0)]), c.array([c.uint(1), ANCHOR])))
conway("vote action id, procedure and anchor", votes(ACTION_ID, detached([c.uint(1), ANCHOR])))
conway("two votes", {19: c.cmap([(VOTER, c.cmap([(ACTION_ID, detached([c.uint(1), c.NULL])),
                                                  (detached([TX_ID, c.uint(1)]), c.array([c.uint(0), c.NULL]))]))])})
conway("committee resignation anchor", {4: c.array([c.array([c.uint(15), COLD, ANCHOR])])})
conway("drep registration anchor", {4: c.array([c.array([c.uint(16), CRED, c.uint(500_000_000), ANCHOR])])})
conway("drep update anchor", {4: c.array([c.array([c.uint(18), CRED, ANCHOR])])})
conway("drep registration anchor in an indefinite certificate",
       {4: c.array([c.array([c.uint(16), CRED, c.uint(500_000_000), ANCHOR], indef=True)])})
# the certificate counts the anchor's fields as its own items: `Sum` of the wrong length
conway("drep update anchor in a certificate that counts its fields",
       {4: c.array([b"\x84" + c.uint(18) + CRED + ANCHOR])})
# the anchor's hash is missing
conway("proposal anchor without its hash", proposals(detached([c.uint(1_000_000), ACCOUNT, INFO, detached([URL])])))
# the certificates are not a record: an empty indefinite list there is the empty list (refused in
# Conway), the items after it read by the body
conway("empty indefinite certificates and items after them",
       {4: detached([c.array([c.uint(0), CRED])])})


# --- auxiliary data -----------------------------------------------------------------------------
for era in ERAS:
    case(era, "auxiliary", "list form", AUX)
    case(era, "auxiliary", "list form without its scripts", detached([c.cmap([(c.uint(674), c.text("x"))])]))
    case(era, "auxiliary", "list form with empty metadata", detached([c.cmap([]), c.array([])]))


# --- whole transactions -------------------------------------------------------------------------
def transaction(era, body_, aux=c.NULL):
    parts = [body_, c.cmap([])] + ([c.TRUE] if since(era, "alonzo") else []) + [aux]
    return c.array(parts)


def with_records(era):
    """A body of `era` holding a detached record: an output's value from Mary, an update before."""
    if since(era, "mary"):
        return body(era, outputs(c.array([ADDRESS, VALUE])))
    return body(era, update(c.cmap([])))


for era in ERAS:
    rich = with_records(era)
    case(era, "transaction", "detached records and auxiliary data", transaction(era, rich, aux=AUX))
    case(era, "transaction", "detached records", transaction(era, rich))
    if not since(era, "alonzo"):
        # `ShelleyTx` is a `RecD` record itself
        case(era, "transaction", "detached transaction", detached([d.body(era), c.cmap([]), c.NULL]))
    else:
        case(era, "transaction", "detached records in an indefinite transaction",
             c.array([rich, c.cmap([]), c.TRUE, c.NULL], indef=True))

# Conway transactions as the node-to-node transaction submission carries them (#702): records in
# each part, and refusals, each a whole transaction, for the protocol's reading of the transaction
# and its id
REDEEMER_UNITS = witness_set(5, c.array([c.array([c.uint(0), c.uint(0), c.uint(0), UNITS])]))
GOVERNANCE = {**votes(ACTION_ID, detached([c.uint(1), ANCHOR])), **proposals(proposal(INFO))}
case("conway", "transaction", "detached records in the witness set",
     c.array([with_records("conway"), REDEEMER_UNITS, c.TRUE, c.NULL]))
case("conway", "transaction", "detached governance records",
     c.array([body("conway", GOVERNANCE), c.cmap([]), c.TRUE, AUX]))
case("conway", "transaction", "detached records in every part of an indefinite transaction",
     c.array([body("conway", {**outputs(c.array([ADDRESS, VALUE])), **GOVERNANCE}), REDEEMER_UNITS,
              b"\xf4", AUX], indef=True))
# refused: the output counts the value's fields as its own items
case("conway", "transaction", "value in an output that counts its fields",
     c.array([body("conway", {1: c.array([b"\x84" + ADDRESS + VALUE])}), c.cmap([]), c.TRUE, c.NULL]))
# refused: the multi-asset is missing, so the next key of the body is read as it
case("conway", "transaction", "value without its multi-asset",
     c.array([body("conway", outputs(c.array([ADDRESS, detached([c.uint(2_000_000)])]))), c.cmap([]),
              c.TRUE, c.NULL]))
# refused: the units' steps are missing, so the validity flag is read as them
case("conway", "transaction", "redeemer units without their steps",
     c.array([with_records("conway"),
              witness_set(5, c.array([c.array([c.uint(0), c.uint(0), c.uint(0), detached([c.uint(1)])])])),
              c.TRUE, c.NULL]))
# refused: the certificates are not a record, and the items after them are read by the body
case("conway", "transaction", "empty indefinite certificates and items after them",
     c.array([body("conway", {4: detached([c.array([c.uint(0), CRED])])}), c.cmap([]), c.TRUE, c.NULL]))


# --- the governance action ids of a node-to-client query ----------------------------------------
# `GetProposals` reads its set of `GovActionId` with the era's decoder at Conway's lowest version
# (#700); each id is a `RecD` record, the set a list of them, optionally under tag 258.
OTHER_TX_ID = c.bstr(c.h(0x67, 32))
DEFINITE_ID = c.array([TX_ID, c.uint(0)])
for name, raw in [
    ("an id", c.array([DEFINITE_ID])),
    ("a detached id", c.array([ACTION_ID])),
    ("a detached id in a tagged set", c.tag(258, c.array([ACTION_ID]))),
    ("a detached id in an indefinite set", c.array([ACTION_ID], indef=True)),
    ("two detached ids", c.array([ACTION_ID, detached([OTHER_TX_ID, c.uint(1)])])),
    ("an id and a detached id", c.array([detached([OTHER_TX_ID, c.uint(1)]), DEFINITE_ID])),
    # refused: a non-empty indefinite list meets the break check before its first field
    ("an indefinite id", c.array([c.array([TX_ID, c.uint(0)], indef=True)])),
    # refused: the index is missing, so the input ends
    ("a detached id without its index", c.array([detached([TX_ID])])),
    # refused: the set counts the id's fields as its own elements
    ("a set that counts a detached id's fields", b"\x83" + ACTION_ID),
    # refused: the index is not a Word16, the transaction id not 32 bytes
    ("a detached id whose index is over a Word16", c.array([detached([TX_ID, c.uint(65536)])])),
    ("an id whose index is over a Word16", c.array([c.array([TX_ID, c.uint(65536)])])),
    ("a detached id whose transaction id is 31 bytes",
     c.array([detached([c.bstr(c.h(0x66, 31)), c.uint(0)])])),
]:
    case("conway", "govactionids", name, raw)


# --- the parts of a block after its header ------------------------------------------------------
def block_body(era, bodies, witnesses, aux=None, indef=False):
    parts = [c.array(bodies, indef=indef), c.array(witnesses, indef=indef), aux if aux is not None else c.cmap([])]
    if since(era, "alonzo"):
        parts.append(c.array([]))
    return b"".join(parts)


for era in ERAS:
    plain = d.body(era)
    rich = with_records(era)
    if era == "conway":
        rich = body(era, {**outputs(c.array([ADDRESS, VALUE])), **votes(ACTION_ID, detached([c.uint(1), ANCHOR]))})
    wits = (c.cmap([(c.uint(5), c.array([c.array([c.uint(0), c.uint(0), c.uint(0), UNITS])]))])
            if since(era, "alonzo") else c.cmap([]))
    case(era, "blockbody", "plain", block_body(era, [plain], [c.cmap([])]))
    case(era, "blockbody", "detached records", block_body(era, [plain, rich], [c.cmap([]), wits]))
    case(era, "blockbody", "detached records in indefinite lists",
         block_body(era, [rich, plain], [wits, c.cmap([])], indef=True))
    if since(era, "allegra"):
        case(era, "blockbody", "detached auxiliary data",
             block_body(era, [plain], [c.cmap([])], aux=c.cmap([(c.uint(0), AUX)])))
        case(era, "blockbody", "detached auxiliary data in an indefinite map",
             block_body(era, [plain], [c.cmap([])], aux=c.cmap([(c.uint(0), AUX)], indef=True)))


def merge(reference):
    """The corpus lines: each case with the verdict and the ids the harness recorded for it."""
    assert len(reference) == len(CASES), "the harness answered a different number of cases"
    lines = []
    for case_, verdict in zip(CASES, reference):
        assert (verdict["name"], verdict["era"], verdict["kind"]) == (case_["name"], case_["era"], case_["kind"])
        result = verdict["result"]
        assert "unsupported" not in result, case_["name"]
        line = {
            "id": case_["id"],
            "name": case_["name"],
            "era": case_["era"],
            "kind": case_["kind"],
            "accepted": result["accepted"],
            "failure": result.get("failure"),
            "txid": result.get("id"),
            "bodyHash": result.get("hash"),
            "txids": result.get("ids"),
            "actions": result.get("actions"),
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
