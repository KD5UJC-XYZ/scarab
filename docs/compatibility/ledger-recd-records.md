<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: RecD records are definite lists, decoder versions 2 to 9 (#668)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-recd-records` in [compatibility.json](../data/compatibility.json). The sections below record the implementation, evidence and remaining limits.

## Status as recorded

partial: below decoder version 12 the reference decodes a `RecD` record through `decodeCount (RecD cn) n`, which reads the length with `decodeRecordNamed` around a decoder that reads nothing (`Decoding/Coders.hs:482`, cardano-ledger `f649f975`), so an indefinite list fails with "Excess terms in array" (`decodeListLikeT`, `Decoding/Decoder.hs:690-708`). `CborRead.record` reads such a record as a definite list only and refuses the indefinite one with the typed `IndefiniteLength`. It reads the update proposal of a body (`Update`, `Shelley/PParams.hs:267`, Shelley to Babbage, in `TxBody.decode` and `LiteLedger.effects`), a redeemer's execution units (`ExUnits`, `Plutus/ExUnits.hs:195-215`, Alonzo to Conway, with the reader of the parameters' units, so a unit is a `Word64` at most `maxBound :: Int64`), and Conway's `GovActionId` (whose index is a `Word16`), `VotingProcedure`, `ProposalProcedure`, `Constitution` and `Anchor` (`Conway/Governance/Procedures.hs`, `BaseTypes.hs:996-1004`). The original bytes of an accepted body or witness set are kept. None of these readers loads a stored ledger state: the governance state has its own codec, and a pre-Conway update is stored as its parameter values.

## Evidence

`RecdRecordsReferenceSuite`: 68 original encodings (`fixtures/reference/recd-records/cases.jsonl`, oracle `scripts/oracle/conway-decoding/recd_cases.py`, harness unchanged), each record definite and indefinite in every era that reads it from a block, the redeemer's units at and over their bounds and the action index at and over its bound. 30 accepted, 38 refused, each refusal recorded with the fragment of the reference failure.

## Explicitly unchecked or unconfirmed

`MaryValue`, the `[coin, multiasset]` value of an output (`Mary/Value.hs:287-306`), and the array form of `AlonzoTxAuxDataRaw` (`Alonzo/TxAuxData.hs:247`) are `RecD` records the reference refuses as indefinite lists, and Scarab still takes them ([#668](https://github.com/KD5UJC-XYZ/scarab/issues/668)). An output's value is also read back from the UTxO store, so its reader needs a rule for stored outputs first (ADR 0006).
