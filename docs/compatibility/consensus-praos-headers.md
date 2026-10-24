<!-- SPDX-License-Identifier: Apache-2.0 -->
# Consensus: Praos headers (Babbage, Conway)

Part of the [compatibility matrix](../compatibility.md); the register entry is `consensus-praos-headers` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: VRF, KES, opcert counters, nonce evolution, leader threshold; typed Praos and threshold causes retained through header/block validation and forging (M11-E/#82); checked before the block is fetched against a ledger view forecast within the stability window (G6)

## Evidence

unit/property; golden Praos/VRF/KES/opcert vectors; devnet nonce comparison (all five Praos nonces); Preview sync (every header threshold-checked)

## Explicitly unchecked or unconfirmed

header protocol-version rule on mainnet (`HeaderProtVerTooHigh`) implemented from source and never exercised (no mainnet run); Byron to Shelley derived from the Byron update state (B13, C1)
