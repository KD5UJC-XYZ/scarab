<!-- SPDX-License-Identifier: Apache-2.0 -->
# Chain selection

Part of the [compatibility matrix](../compatibility.md); the register entry is `chain-selection` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: longer chain, opcert issue number, VRF tie-break, max rollback k, several peers

## Evidence

unit/property with the exact upstream cases (same issuer and slot for the opcert rule, inclusive 5-slot window, equal VRF keeps the chain; antisymmetry, longer wins, never more than k rolled back, order independence); devnet `relay` and `pool` (slot battles, deepest rollback 1)

## Explicitly unchecked or unconfirmed

none known for the single-chain order; confirmed against ouroboros-consensus 4.2.0.0 `comparePraos` (B1); the order is not transitive in Conway by design (upstream notes it)
