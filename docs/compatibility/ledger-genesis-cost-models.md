<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: genesis cost models (#369)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-genesis-cost-models` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented: the Alonzo `costModels` take a PlutusV1 model of exactly 166 parameters, an array or an object that names all of them (read in the order of the parameter list, the six older V1 names included), and ignore other languages; `extraConfig.costModels` take every language with any length and an `Unknown` map; the Conway `plutusV3CostModel` is an array of exactly 251; refused otherwise with a typed `GenesisCostModel` problem, as `parseCostModels` and `parseCostModelAsArray` (cardano-ledger `f649f975`, `CostModels.hs:138-215,400-436`, `Alonzo/Genesis.hs:110-114,222-242`, `Conway/PParams.hs:1095`)

## Evidence

`GenesisCostModelsSuite`: 135 retained reference cases (64 Alonzo genesis, 13 Conway genesis, 22 `parseCostModels`, 36 `mkCostModel`) read to the values and the refusal category; `CostModelLengthSuite`: 5,661 Plutus 1.70.0.0 evaluations under cost-model lists of 0 to 411 values, all `Int64` minimum, `-1`, 0 or maximum, negated and mixed-sign lists, compared with the node's evaluator (stage and exact budget, none excluded); `ProtocolParamsSuite`; every real genesis fixture still loads

## Explicitly unchecked or unconfirmed

the cases were run against the unchanged released Alonzo, Conway and core packages and Plutus 1.70.0.0 ([cases and pins](../../fixtures/reference/genesis-cost-models/README.md)); Plutus pads a short list with `maxBound` and drops extra values, and so does Scalus 1.4.0; under negative and extreme parameters the evaluator agrees because it uses the reference's budget arithmetic (`ReferenceCosting`, `ReferenceBudget`; Scalus's own differs on 506 observations, [#485](https://github.com/KD5UJC-XYZ/scarab/issues/485), IN-84); `mkCostModel` accepted all 4,188 lists of a one-parameter-at-a-time sweep, so its `CostModelApplyError` is not mirrored (a finite grid, not a proof); the other fields of the genesis files are still read with defaults where the Haskell parsers require them ([#497](https://github.com/KD5UJC-XYZ/scarab/issues/497)); the node's merge of the extra models is IN-65
