<!-- SPDX-License-Identifier: Apache-2.0 -->
# Plutus at protocol 12: PlutusV4 and case on Data (#54)

Part of the [compatibility matrix](../compatibility.md); the register entry is `plutus-v4-dijkstra` in [compatibility.json](../data/compatibility.json). The sections below record the implementation, evidence and remaining limits.

## Status as recorded

partial: the Dijkstra the node implements is the Leios testnet build's (cardano-node `b004fe0c`, cardano-ledger `9df9f98c`, [decisions](../decisions.md), #711), whose node links plutus-core and plutus-ledger-api 1.71.0.0 (`261766ad`). From protocol 12 that plutus makes program version 1.2.0 available in every language, lets a 1.2.0 program `case` on `Data` (`defaultCaserBuiltinFor`), and evaluates PlutusV1 and PlutusV2 in builtin semantics variant F and PlutusV3 and PlutusV4 in G, which drop the integer unlifting bounds of D and E and keep those on byte strings ([`SemanticsVariant`](../../plutus/src/main/scala/xyz/kd5ujc/scarab/plutus/SemanticsVariant.scala)). Below protocol 12 its tables, caser and variants are those of 1.70.0.0. cardano-ledger `9df9f98c` runs a PlutusV4 script (language 3, hash tag 4) through plutus's PlutusV3 functions, cost model parameter names and evaluation context, and decodes it from protocol 12 only; Scarab does the same ([`PlutusLanguage.evaluatedAs`](../../uplc/src/main/scala/xyz/kd5ujc/scarab/uplc/PlutusLanguage.scala)). Its language view is key 3 with a definite list (`ScriptIntegrity.plutusLanguageViews`). `DijkstraScriptContexts` builds the PlutusV4 `ScriptContext` of a Dijkstra transaction and collects a transaction's scripts with their arguments as `mkDijkstraStAnnTopTx` does.

The node does not reach protocol 12: `HardForkConfig.MaxMajorProtVer` is 11 and the envelope check refuses every block once the ledger's major version passes it (`ObsoleteNode`), and the Dijkstra era is not entered.

## Evidence

- `PlutusConformance171Suite`: the complete evaluation corpus of plutus 1.71.0.0 (1,009 cases) on the machine production builds for PlutusV4 at protocol 12, under the upstream executable's profile (variant G, counting budget, `defaultCaserBuiltinFor newestPV`): every result and budget equal to the recorded ones (2026-11-04); its 906 cases with a Flat form also pass from their `.flat` files. The 1.70.0.0 corpus still passes at protocol 11 (`PlutusConformanceSuite`).
- `CaseOnDataSuite`, `PlutusAvailabilitySuite`, `UnliftingBoundsSuite`: case on `Data` by protocol and program version, the availability tables at 12, the variants by language and protocol, and the bounds of F and G.
- `DijkstraScriptContextsSuite`: eleven transactions of [leios-testnet-contexts](../../fixtures/reference/leios-testnet-contexts/README.md), whose contexts the Leios testnet build collected through the oracle's "context" kind: the arguments of every PlutusV4 script of four transactions (spending, minting, certificates, withdrawals, votes, proposals with parameter changes, a guard with two sub-transactions, scripts inside a sub-transaction) and seven refusals equal the reference's; `DijkstraContextWireSuite`: the failure payload of each of those contexts (`PlutusWithContext`) equals the reference's bytes.

## Explicitly unchecked or unconfirmed

- The contexts of PlutusV1 to PlutusV3 scripts in a Dijkstra transaction (`guardDijkstraFeaturesForPlutusV1toV3`, the Dijkstra TxInfos of those languages) are not built: they are collected as `Untranslated`. Phase 2 and UTXOW of the Dijkstra era do not use the contexts yet, and `NoCostModel` is the caller's.
- The cost model parameter names are those of 1.70.0.0 (361 for PlutusV3 and PlutusV4); 1.71.0.0 has 369, the eight new ones for `keepPolicies` and `dropPolicies`, which no protocol version enables. No reader of this node depends on the table: a genesis cost model given by name is read by its first 251 names, every other list by position at any length, and the values after the 361st cost only those builtins (`CostModelNameTablesSuite`). The owner decided on 2026-11-04 that the mainnet lineage reads by the 1.70.0.0 names and the Leios testnet by the 1.71.0.0 names; that rule has no reader to apply to yet, and one that names parameters past the 251st must select the table by the network.
- The pre-Dijkstra ledger has no PlutusV4 script kind (the persisted UTxO format has none).
