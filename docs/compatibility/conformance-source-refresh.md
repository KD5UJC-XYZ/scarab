<!-- SPDX-License-Identifier: Apache-2.0 -->
# Conformance source refresh (#273)

Part of the [compatibility matrix](../compatibility.md); the register entry is `conformance-source-refresh` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: literal/cached roots publish before startup/reload DNS; standalone Conway UTXO enforces nonempty spending and existence of all input roles without redeemers; actual source-watch roots are checked individually

## Evidence

Controlled DNS/runtime and old-code failures; exact released-native missing-collateral refusal; 39 distinct lawful native/formal judgments; complete published pool-parameter bytes; 54 released Plutus admission/stage comparisons and separate historical-bounds observations. [As built and fixtures](../design/upstream-conformance.md#source-refresh-implementation)

## Explicitly unchecked or unconfirmed

Generated-Haskell formal backports are executable observations, not regenerated Agda proofs. Original invalid caches/crashes remain retained. Unsupported direct query examples and future-era behavior are explicit; released Plutus historical policy remains authoritative; live provenance propagation is implemented; missing historical data and operational rollout remain #129
