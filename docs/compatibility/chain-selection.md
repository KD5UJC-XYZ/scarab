<!-- SPDX-License-Identifier: Apache-2.0 -->
# Chain selection

Part of the [compatibility matrix](../compatibility.md); the register entry is `chain-selection` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: longer chain, opcert issue number, VRF tie-break, max rollback k, several peers

## Evidence

unit/property with the exact upstream cases (same issuer and slot for the opcert rule, inclusive 5-slot window, equal VRF keeps the chain; antisymmetry, longer wins, never more than k rolled back, order independence); devnet `relay` and `pool` (slot battles, deepest rollback 1)

## Explicitly unchecked or unconfirmed

none known for the single-chain order; confirmed against ouroboros-consensus 4.2.0.0 `comparePraos` (B1); the order is not transitive in Conway by design (upstream notes it)

The Leios build's acquired-endorser-block gate (#54) mirrors ouroboros-consensus 2416493b: a ranking block whose certificate references an endorser block whose closure is not acquired is hidden from candidate construction, as a tip and as a successor, and reconsidered when the closure completes; the acquired set is seeded from the endorser-block store when the chain database opens, grown from the store's completions and pruned below the immutable tip (`LeiosChainSelSuite`, `LeiosEbGateSuite`). Unchecked: the gate has been run on forged blocks given the Leios fields of an announcing and a certifying block, not on a Dijkstra chain, which the node does not apply yet (#826); nothing writes the store at runtime yet (#830), so on a Leios network every certifying block stays parked; the reference's forgetting of certifying blocks off the initial selection at open (`forgetLeiosCertsAtStartUpExcept`) is not mirrored (#54).
