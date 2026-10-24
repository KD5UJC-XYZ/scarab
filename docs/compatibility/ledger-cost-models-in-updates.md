<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: cost models in parameter updates (AUD3-06, #332)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-cost-models-in-updates` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: field 18 of a pre-Conway update proposal (decoder version below 9) accepts only PlutusV1 with 166 and PlutusV2 with 175 `Int64` parameters and refuses any other language or length; a Conway parameter-change proposal (version 9 and above) takes a `Word8` language with any list length, keeps unknown languages, refuses values outside `Int64` and a repeated language; refused when the transaction is decoded, as `decodeCostModels` (cardano-ledger `f649f975`, `CostModels.hs:302-337,426-440`)

## Evidence

`CostModelDecodeSuite`: 98 retained reference transactions replayed through `Tx.fromEnvelope` for both `Era.Alonzo` and `Era.Babbage` (supported languages, lengths, unsupported and non-`Language` keys, `Int64` bounds, duplicate-language collapse, malformed entries), plus the Conway cases through `Proposal.decode`; persisted parameters, snapshots and genesis files are read by their own codecs and unchanged

## Explicitly unchecked or unconfirmed

the Conway cases were run through `cardano-cli debug transaction view` of the 11.1.3 reference (language 256 and -1, values outside `Int64` and a repeated language refused; short and long lists, unknown languages and the `Int64` bounds accepted), the pre-Conway cases were run against the unchanged released Alonzo (decoder version 5) and Babbage (version 7) decoders, 49 cases in each era with equal verdicts, as retained fixtures ([scope and pins](../../fixtures/reference/cost-models/README.md)), the Conway ones were measured with the CLI and their verdicts are recorded in the suite's documentation, not as retained bytes; Plutus `mkCostModel` (1.70.0.0) pads a short list and ignores extra values with warnings only, so no further refusal is mirrored; genesis cost-model JSON strictness (`parseCostModels`) and an update applied from persisted state are not tightened
