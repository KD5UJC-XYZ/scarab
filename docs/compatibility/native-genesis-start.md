<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native genesis start (#488)

Part of the [compatibility matrix](../compatibility.md); the register entry is `native-genesis-start` in [compatibility.json](../data/compatibility.json).

## Status as recorded

`NodeSetup` constructs native state through `ByronMirror` or `GenesisStart.ledger` and `GenesisMirror`. A Shelley-based start translates the Byron genesis UTxO to its first era and applies that era's genesis injection. A Byron start applies no injection; its `FromByron` hand-over context uses `ProtocolParams.translatedFromGenesis`. Supplied Alonzo `extraConfig.costModels` are still parsed, so ignoring their application does not bypass existing validation. Base genesis models remain available to the later era translations. Alonzo's injection overrides only current parameters for a Shelley-based start. Each injected output must fit the reference's compact value at construction; this output bound does not restrict the signed `Coin` used for reserve arithmetic.

Reference: ouroboros-consensus `82ecba32`, `Cardano/Node.hs:933-974` (`mkInitExtLedgerStateCardano`, `perEraInjections`, Byron identity); cardano-ledger `f649f975`, `Alonzo/Genesis.hs:222-242` (parse the optional extra configuration) and `Alonzo/Transition.hs:35-66` (`injectIntoTestState`, `alonzoInjectCostModels`, current parameters only). The `legacy` module and its flat-state conversion are removed.

## Evidence

`GenesisStartSuite` checks native genesis funds, accounts, stake snapshots, governance state, current-only cost-model injection and typed refusals. Its Byron regression constructs the real fixture's native Byron state, performs `Handover.toShelley` and the translations through Alonzo, and requires the base PlutusV1 model to survive without the injected PlutusV2 model. A separate malformed-model case requires the unused injection's existing typed validation to remain active. Output-construction tests cover zero and Word64 maximum, reject negative and over-Word64 amounts, and exercise a negative enterprise-address fund through `NodeSetup`. A Byron control confirms that unused initial-fund injections do not construct outputs.

`DuplicateWithdrawalsLedgerSuite`, carried from [#496](https://github.com/KD5UJC-XYZ/scarab/pull/496), keeps the value-conservation regression on native ledger state when the legacy test module is deleted. These are scoped unit/source checks; full scenario evidence belongs in a dated evidence-log entry.

## Explicitly unchecked or unconfirmed

Genesis pool relays/metadata and hash-checked `InjectionFromFile` support remain [#499](https://github.com/KD5UJC-XYZ/scarab/issues/499), approved for separate reference-alignment changes. Existing required-field/default and malformed-input parser differences remain [#497](https://github.com/KD5UJC-XYZ/scarab/issues/497). Preserving validation of the tested malformed extra cost model does not establish complete genesis-parser conformance. #496 is a required predecessor of the legacy deletion. Signed reserve arithmetic awaits [#492](https://github.com/KD5UJC-XYZ/scarab/issues/492); the final validation must include initial funds whose total exceeds reserves without any individual output exceeding its compact bound.
