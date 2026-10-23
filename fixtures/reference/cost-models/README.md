# Pre-Conway cost-model decoding fixtures

Ninety-eight synthetic public transactions (49 cases, each in the Alonzo and in the Babbage era),
measured on 2026-10-22 against the unchanged released Haskell ledger libraries used by node 11.1.3.
`legacy-update.json` retains each original transaction as hex, the typed refusal Scarab must give
(`expect` and `detail`) and the reference verdict: accepted with the cost models it kept, or the
original `DecoderError`. JSON whitespace was formatted for review; the transaction bytes are
unchanged. The oracle source and [offline recipe](../../../scripts/oracle/cost-models/README.md)
are committed; `provenance.json` records the base and measured image identities, the package
versions, the pinned source comparison and the output hashes.

Each transaction has one update proposal (`{6: [{genesis key: {18: cost models}}, epoch]}`) in the
body; decoder version 5 (Alonzo) and 7 (Babbage) select `decodeCostModelsFailing` /
`decodeCostModelLegacy` (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/CostModels.hs:306-337`
at cardano-ledger `f649f975`, byte for byte the released source).

| group | cases | reference verdict |
|---|---|---|
| supported | 166 PlutusV1, 175 PlutusV2, both in either order, the empty map, both `Int64` bounds in the first parameter of each language, an indefinite-length list and map | accepted |
| repeated language | V1, V2 and V1-V2-V1 with different values in each entry | accepted; the last entry survives |
| bad length | V1 with 0, 1, 165, 167, 175, 251; V2 with 0, 1, 166, 174, 176, 251; a repeated V1 with the first or second list short | refused: `Expected array with N entries, but encoded array has M entries.` |
| unsupported language | key 2 and 3 (named PlutusV3, PlutusV4) with a full-size, empty or `Int64`-overflowing list, and after a valid V1 | refused: `Legacy CostModel decoding is not supported for ...`, before the values are read |
| not a `Language` | key 4, 255, 256, -1 (also with an overflowing value); a text key | refused: `Failed to decode an Enum: N for TypeRep: Language`; a text key `expected int` |
| outside `Int64` | 2^63 and -2^63-1 in the first parameter of V1 and V2; 2^63 in a list that is also one entry short | refused: `expected int`, before the length is counted |
| not an integer array | a text parameter, an entry that is an integer or null, a bignum parameter (2^64 and the small value 1) | refused: `expected int` or `expected list len or indef` |

`CostModelDecodeSuite` decodes the same transactions through `Tx.fromEnvelope` for `Era.Alonzo`
and `Era.Babbage`, and requires acceptance, the kept values, the typed `LedgerDecodeError` of the
case and the retained verdict to agree. The Alonzo and Babbage verdicts of every case are equal.

Scope: independent decoder measurement of field 18 of an update proposal, not transaction
admission, the application of an update, genesis cost-model parsing (follow-up
[#369](https://github.com/KD5UJC-XYZ/scarab/issues/369)) or Plutus evaluation. Accepted rows fill
the lists with ascending integers (and one extreme in the first place), so they show that
`mkCostModel` accepts those values; they do not exhaust the values it could refuse. The Conway
rule (version 9 and above) is not in this corpus; its measurements by `cardano-cli debug
transaction view` are described in `CostModelDecodeSuite`. Shelley, Allegra and Mary updates have
no field 18 and are not measured. The `cardano-ledger-binary` source file that holds
`decodeMapByKey` differs from the pin in two IPv4 lines only (see `provenance.json`).
