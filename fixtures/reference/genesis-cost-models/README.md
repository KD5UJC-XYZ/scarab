# Genesis cost-model fixtures

135 cases measured on 2026-10-25 against the unchanged released Haskell ledger libraries used by node
11.1.3 (cardano-ledger `f649f975`, plutus 1.70.0.0 `507cab8c`), for
[#369](https://github.com/KD5UJC-XYZ/scarab/issues/369). The oracle source and
[offline recipe](../../../scripts/oracle/genesis-cost-models/README.md) are committed; `provenance.json`
records the image identities, package versions and output hashes.

`genesis.json` holds one measurement per row: `kind`, the `input` (the genesis rows are a tracked
genesis file with a few keys replaced or removed: `fixtures/preview/genesis/alonzo-genesis.json`,
`fixtures/eras/genesis/alonzo-genesis.json` and `fixtures/preview/genesis/conway-genesis.json`), the
typed refusal Scarab must give (`category`, or `accepted`) and the reference verdict.

| group | cases | reference verdict |
|---|---|---|
| Alonzo `costModels` (strict, PlutusV1 only: `parseCostModels False [PlutusV1]`) | the preview object of 166 named parameters, an array of 166, names in another order, `Int64` bounds, whole numbers written `1.0` and `1e3` | accepted; named parameters are ordered by the parameter list, not by the file |
| | arrays of 0, 165, 167 and 343; an object with one name more | refused: `Number of parameters supplied N does not match the expected number of 166` |
| | an object with a name missing or replaced, the six older V1 names replaced by the new ones, an empty object | refused: `Parameter name missing from cost model: ...` |
| | a value that is text, null, a fraction, 2^63 or below -2^63 (also in an unused extra name) | refused: `parsing Int64 failed` |
| | PlutusV1 absent, null or only PlutusV2 present; `costModels` absent, null or an array | refused: `Expected "PlutusV1" cost model to be supplied`, `key "costModels" not found`, `expected Object` |
| | PlutusV2, PlutusV3, a bad PlutusV2, `Unknown` and an unknown language name beside PlutusV1 | accepted: the other keys are never read |
| Alonzo `extraConfig.costModels` (lenient, every language, `Unknown`) | PlutusV1 and V2, V2 of 0, 100 and 300 parameters, V3, V4, `Int64` bounds, an empty object, `extraConfig` null or without models | accepted with the original values; no length rule |
| | `Unknown` keys: 4 and 255 stay unknown, 0 to 3 become valid models (also when short), a key written `04`, `+4`, `4.0`, `1e1` or `-0` | accepted |
| | `Unknown` keys 256, `x`, `4.5`, ` 4`, `4 `, `0x4`, the empty key and `-4`; a non-`Int64` value; `Unknown` or `extraConfig` not an object; a V2 object lacking names; a V2 that is text | refused |
| Conway `plutusV3CostModel` (`parseCostModelAsArray False PlutusV3`) | the preview array of 251, `Int64` bounds | accepted |
| | 0, 250, 252 and 361 values | refused: `Number of parameters supplied N does not match the expected number of 251` |
| | an object, text or null; absent; a value outside `Int64` or text | refused: `expected Array`, `key "plutusV3CostModel" not found`, `parsing Int64 failed` |
| `parseCostModels` | lenient and strict over all languages, restricted to PlutusV1, with `Unknown`, empty | as above |
| `mkCostModel` | PlutusV1, V2 and V3 with 0, 1, the initial count, one more, one fewer than the full list, the full list, more; all `Int64` minimum, `-1`, 0 and maximum | accepted: Plutus pads a short list with `maxBound` and drops the extra values (`tagWithParamNames`) |

`sweep.json` records that `mkCostModel` accepted all 4,188 lists that change one parameter of PlutusV1, V2 or V3 at a time to `Int64`'s minimum, `-1`, 0 or maximum: no `CostModelApplyError` was reachable from the values of that grid.

`evaluation.jsonl` holds 3,441 observations of unchanged Plutus 1.70.0.0 (111 programs: the first upstream program of each builtin and the first of each generated family of the [costing corpus](../plutus-costing/README.md), under the 31 lists of `evaluation-models.json`): the stage and the exact budget. The lists are the pinned default parameters of the profile cut or extended to 0, 1, 100, 166, 175, 251, 300, 343, 344, 360, 361, 362, 393, 400 or 411 values, and a list of 343 or 361 values all `Int64` minimum, `-1`, 0 or maximum, with PlutusV1 at protocols 5 and 11, PlutusV2 at 7 and 11 and PlutusV3 at 11.

`GenesisCostModelsSuite` reads every row through `GenesisCostModels` and requires acceptance, the kept values and the typed refusal to agree; `CostModelLengthSuite` evaluates every observation with Scalus 1.4.0 and requires the same stage and budget (all lists but the all-minimum ones, [#485](https://github.com/KD5UJC-XYZ/scarab/issues/485)).

Scope: the cost-model parts of the Alonzo and Conway genesis files and Plutus evaluation under a list of any length; not the other fields of the genesis files (the Haskell parsers require every field, Scarab's reader keeps its defaults for the others), the application of a protocol-parameter update or the merge of the extra models by the node (IN-65). The sweep is a finite grid: it does not prove that `mkCostModel` accepts every list, and `GenesisCostModels` does not mirror a refusal by it. The released source files `CostModels.hs`, `Alonzo/Genesis.hs` and `Conway/PParams.hs` were compared with the pin and are byte-identical (`provenance.json`); the other files of those packages were not compared.
