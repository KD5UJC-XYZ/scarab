#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the `RecD` record decoding oracle (#668).

Below decoder version 12 the reference decodes a record built with `RecD` through
`decodeCount (RecD cn) n = decodeRecordNamed "RecD" (const n) (pure (n, cn))`
(`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Coders.hs:482`, cardano-ledger
f649f975). The inner decoder reads nothing, so a non-empty indefinite list meets the check for its
break before its first field and fails with "Excess terms in array" (`decodeListLikeT`,
`Decoding/Decoder.hs:690-708`). An empty indefinite list passes that check and the fields are read
from the items after its break (#687); every indefinite list here holds the record's fields. `RecD` has no version guard
of its own; each instance below takes it under `ifDecoderVersionAtLeast (natVersion @12)`, or with
no guard at all, so one decoder version per era (Shelley 2 to Conway 9) is every version that reads
these records from a block.

Each record a block can hold is written as a definite list and as an indefinite one:
- `Update` (`Shelley/PParams.hs:267`), the update proposal of a body, Shelley to Babbage;
- `ExUnits` of a redeemer (`Plutus/ExUnits.hs:195-215`), Alonzo to Conway, in the list form and in
  Conway's map form, with its units at and over `maxBound :: Int64` and over `Word64`;
- `GovActionId` (`Conway/Governance/Procedures.hs:182-190`) as a vote's key and as the previous
  action of each action that names one, with its index (`GovActionIx`, a `Word16`, 162) at and over
  its bound; `VotingProcedure` (456-466), `ProposalProcedure` (522-533), `Constitution` (985-995)
  and `Anchor` (`BaseTypes.hs:996-1004`) in a proposal, a vote, a constitution and the
  certificates that carry one (15, 16 and 18).

The committed corpus is `fixtures/reference/recd-records/cases.jsonl`, written in two steps as for
`definite_text_cases.py`:

    python3 scripts/oracle/conway-decoding/recd_cases.py --inputs /tmp/recd-inputs.json
    python3 scripts/oracle/conway-decoding/recd_cases.py --inputs /tmp/recd-inputs.json \\
        --reference /tmp/recd-reference.json --out fixtures/reference/recd-records/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402  (the CBOR helpers)
import definite_bytes_cases as d  # noqa: E402  (the era bodies)
import definite_text_cases as t  # noqa: E402  (the merge)
import pre_conway_cases as p  # noqa: E402  (the genesis key of an update)

PPUP = ["shelley", "allegra", "mary", "alonzo", "babbage"]
PLUTUS = ["alonzo", "babbage", "conway"]
INT64_MAX = 2**63 - 1
WORD64_MAX = 2**64 - 1
CASES = []

# Fragments of the reference failure for each refusal.
EXCESS = "Excess terms in array"
INT64 = "values must not exceed maxBound :: Int64"
WORD = "expected word"
WORD16 = "expected word16"


def case(era, kind, name, raw, refused=None):
    """`refused` is the reference's rule and Scarab's error case."""
    name = f"{era} {kind} {name}"
    rule, expect = refused or (None, None)
    CASES.append(
        {"id": c.case_id(name), "name": name, "era": era, "kind": kind, "rule": rule, "expect": expect,
         "bytes": raw.hex()}
    )


def forms(era, kind, name, build):
    """`build` of a list writer, as a definite list and as an indefinite one."""
    case(era, kind, f"{name}: definite", build(c.array))
    case(era, kind, f"{name}: indefinite", build(lambda xs: c.array(xs, indef=True)),
         (EXCESS, "IndefiniteLength"))


def integer(v):
    return c.bignum(v) if v > WORD64_MAX else c.uint(v)


# --- the update proposal of a body, Shelley to Babbage -------------------------------------------
for era in PPUP:
    forms(era, "body", "update", lambda rec, era=era: d.body(
        era, {6: rec([c.cmap([(p.GENESIS, c.cmap([(c.uint(0), c.uint(44))]))]), c.uint(10)])}))


# --- the execution units of a redeemer, Alonzo to Conway -----------------------------------------
def redeemer_list(units):
    return d.wits(5, c.array([c.array([c.uint(0), c.uint(0), c.uint(0), units])]))


def redeemer_map(units):
    return d.wits(5, c.cmap([(c.array([c.uint(0), c.uint(0)]), c.array([c.uint(0), units]))]))


for era in PLUTUS:
    shapes = [("redeemer list", redeemer_list)] + ([("redeemer map", redeemer_map)] if era == "conway" else [])
    for label, shape in shapes:
        forms(era, "witnesses", f"{label} execution units", lambda rec, shape=shape: shape(rec([c.uint(1), c.uint(2)])))
        case(era, "witnesses", f"{label} execution units at the Int64 bound",
             shape(c.array([integer(INT64_MAX)] * 2)))
        case(era, "witnesses", f"{label} execution units with memory over the Int64 bound",
             shape(c.array([integer(INT64_MAX + 1), c.uint(1)])), (INT64, "NumberOutOfRange"))
        case(era, "witnesses", f"{label} execution units with steps over the Int64 bound",
             shape(c.array([c.uint(1), integer(INT64_MAX + 1)])), (INT64, "NumberOutOfRange"))
        case(era, "witnesses", f"{label} execution units with steps over Word64",
             shape(c.array([c.uint(1), integer(WORD64_MAX + 1)])), (WORD, "Expected"))


# --- Conway governance --------------------------------------------------------------------------
URL = c.text("https://example.invalid/a")
DATA_HASH = c.bstr(c.h(0x44, 32))
ACCOUNT = c.bstr(b"\xe1" + c.h(0x55, 28))
CRED = c.array([c.uint(0), c.bstr(c.KEY_HASH)])
COLD = c.array([c.uint(0), c.bstr(c.h(0x73, 28))])
TX_ID = c.bstr(c.h(0x66, 32))
DEFINITE_ANCHOR = c.array([URL, DATA_HASH])


def anchor(rec):
    return rec([URL, DATA_HASH])


def conway(name, extra, refused=None):
    case("conway", "body", name, d.body("conway", extra), refused)


def conway_forms(name, extra):
    forms("conway", "body", name, lambda rec: d.body("conway", extra(rec)))


def proposals(*items):
    return {20: c.array(list(items))}


def proposal(action, rec=c.array, anchor_=DEFINITE_ANCHOR):
    return rec([c.uint(1_000_000), ACCOUNT, action, anchor_])


INFO = c.array([c.uint(6)])


def votes(action_id, procedure):
    return {19: c.cmap([(c.array([c.uint(2), c.bstr(c.KEY_HASH)]), c.cmap([(action_id, procedure)]))])}


def action_id(rec=c.array, index=c.uint(0)):
    return rec([TX_ID, index])


VOTE = c.array([c.uint(1), c.NULL])


def certs(*items):
    return {4: c.array(list(items))}


# The previous action of each action that names one (`Summands "GovAction"`, `Procedures.hs:880-895`).
def previous(prev):
    return {
        "parameter change": c.array([c.uint(0), prev, c.cmap([(c.uint(0), c.uint(44))]), c.NULL]),
        "hard fork": c.array([c.uint(1), prev, c.array([c.uint(10), c.uint(0)])]),
        "no confidence": c.array([c.uint(3), prev]),
        "committee update": c.array([c.uint(4), prev, c.array([]), c.cmap([]),
                                     c.tag(30, c.array([c.uint(1), c.uint(2)]))]),
        "new constitution": c.array([c.uint(5), prev, c.array([DEFINITE_ANCHOR, c.NULL])]),
    }


conway_forms("proposal procedure", lambda rec: proposals(proposal(INFO, rec=rec)))
conway_forms("proposal anchor", lambda rec: proposals(proposal(INFO, anchor_=anchor(rec))))
conway_forms("constitution", lambda rec: proposals(proposal(
    c.array([c.uint(5), c.NULL, rec([DEFINITE_ANCHOR, c.NULL])]))))
conway_forms("constitution anchor", lambda rec: proposals(proposal(
    c.array([c.uint(5), c.NULL, c.array([anchor(rec), c.NULL])]))))
for label in previous(c.NULL):
    conway_forms(f"{label} previous action id",
                 lambda rec, label=label: proposals(proposal(previous(action_id(rec))[label])))
conway(
    "no confidence previous action id at the index bound",
    proposals(proposal(previous(action_id(index=c.uint(2**16 - 1)))["no confidence"])),
)
conway(
    "no confidence previous action id over the index bound",
    proposals(proposal(previous(action_id(index=c.uint(2**16)))["no confidence"])),
    (WORD16, "NumberOutOfRange"),
)
conway_forms("vote action id", lambda rec: votes(action_id(rec), VOTE))
conway_forms("voting procedure", lambda rec: votes(action_id(), rec([c.uint(1), c.NULL])))
conway_forms("vote anchor", lambda rec: votes(action_id(), c.array([c.uint(1), anchor(rec)])))
conway("vote action id at the index bound", votes(action_id(index=c.uint(2**16 - 1)), VOTE))
conway("vote action id over the index bound", votes(action_id(index=c.uint(2**16)), VOTE),
       (WORD16, "NumberOutOfRange"))
conway_forms("committee resignation anchor", lambda rec: certs(c.array([c.uint(15), COLD, anchor(rec)])))
conway_forms("drep registration anchor",
             lambda rec: certs(c.array([c.uint(16), CRED, c.uint(500_000_000), anchor(rec)])))
conway_forms("drep update anchor", lambda rec: certs(c.array([c.uint(18), CRED, anchor(rec)])))


# --- the value of an output, Mary to Conway -----------------------------------------------------
# `MaryValue` (`Mary/Value.hs:287-311`): `decodeMaryValue` peeks the token type and takes an
# indefinite list into `decodeValuePair`, whose `RecD` refuses it. An output reads it in each of its
# forms (`ShelleyTxOut`, `AlonzoTxOut`, `BabbageTxOut`), and a collateral return is an output.
VALUE_ERAS = ["mary", "alonzo", "babbage", "conway"]
COIN = c.uint(2_000_000)
ASSETS = c.cmap([(c.bstr(c.h(0x88, 28)), c.cmap([(c.bstr(b"a"), c.uint(1))]))])
DATUM_HASH = c.bstr(c.h(0x58, 32))


def value(rec):
    return rec([COIN, ASSETS])


for era in VALUE_ERAS:
    after_alonzo = era != "mary"
    after_babbage = era in ("babbage", "conway")
    forms(era, "body", "output value", lambda rec, era=era: d.body(
        era, {1: c.array([c.array([c.ADDRESS, value(rec)])])}))
    if after_alonzo:
        forms(era, "body", "output with a datum hash value", lambda rec, era=era: d.body(
            era, {1: c.array([c.array([c.ADDRESS, value(rec), DATUM_HASH])])}))
    if after_babbage:
        forms(era, "body", "map output value", lambda rec, era=era: d.body(
            era, {1: c.array([c.cmap([(c.uint(0), c.ADDRESS), (c.uint(1), value(rec))])])}))
        forms(era, "body", "collateral return value", lambda rec, era=era: d.body(
            era, {16: c.array([c.ADDRESS, value(rec)])}))


# --- the list form of auxiliary data, Allegra to Conway -----------------------------------------
# `AllegraTxAuxDataRaw` (`Allegra/TxAuxData.hs:185-210`) and, from Alonzo, `decodeAllegra` of
# `AlonzoTxAuxDataRaw` (`Alonzo/TxAuxData.hs:245-251`), which `decodeTxAuxDataByTokenType` (302-312)
# picks for a list of either kind: `[metadata, native scripts]`, a `RecD` record. Each is read in a
# whole transaction, whose decoder reads its auxiliary data with the decoder a block's uses.
AUX_ERAS = ["allegra", "mary", "alonzo", "babbage", "conway"]
METADATA = c.cmap([(c.uint(674), c.uint(1))])


def transaction(era, aux):
    flag = [c.TRUE] if era in PLUTUS else []
    return c.array([d.body(era), c.cmap([])] + flag + [aux])


for era in AUX_ERAS:
    forms(era, "transaction", "auxiliary data list",
          lambda rec, era=era: transaction(era, rec([METADATA, c.array([c.NATIVE])])))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", help="write the array of original encodings the harness reads")
    parser.add_argument("--reference", help="the harness output for those inputs, to merge")
    parser.add_argument("--out", help="write the corpus lines (needs --reference)")
    args = parser.parse_args()
    t.CASES[:] = CASES
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
            lines = t.merge(json.load(f))
        with open(args.out, "w") as f:
            f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
