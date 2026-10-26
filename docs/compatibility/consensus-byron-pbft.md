<!-- SPDX-License-Identifier: Apache-2.0 -->
# Consensus: Byron (PBFT)

Part of the [compatibility matrix](../compatibility.md); the register entry is `consensus-byron-pbft` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: delegate signature (Byron Ed25519 profile, #392 row below), current delegation (genesis and on-chain certificates), last-signed-slot rule, `k`-block signature window and threshold, body proofs, epoch length from genesis; every main block is fully checked (no `unchecked` outcome)

## Evidence

`ByronPreprodSuite` (the whole preprod Byron era replayed from genesis), `EraTransitionSuite`, devnet e2e (all Byron blocks verified, on schedule); preprod sync

## Explicitly unchecked or unconfirmed

the round-robin leader schedule is reported (`offSchedule`), not enforced, because upstream validation does not enforce it; light delegation is a decode error as upstream; mainnet not synced; Byron headers are not validated header-first (their PBFT checks need the Byron delegation state, validated with the block); Byron block production (delegate-key signing, PBFT forging) is not planned (decision, [#223](https://github.com/KD5UJC-XYZ/scarab/issues/223), [H7](../roadmap.md)): Byron production ended on mainnet in 2020, every live network forges with Praos only, and the devnets need no Byron forger (the reference cluster forges the Byron blocks of the era-walking devnets)
