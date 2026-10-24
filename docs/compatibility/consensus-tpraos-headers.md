<!-- SPDX-License-Identifier: Apache-2.0 -->
# Consensus: TPraos headers (Shelley to Alonzo)

Part of the [compatibility matrix](../compatibility.md); the register entry is `consensus-tpraos-headers` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: overlay schedule, two VRF certificates, 512-bit leader value; typed protocol/state, vocabulary, VRF-verdict and threshold failures (M11-E/#82)

## Evidence

unit/property; devnet e2e (`leaderUnchecked` 0 in every era); Preview Alonzo/Babbage sync (every non-overlay header threshold-checked)

## Explicitly unchecked or unconfirmed

overlay-slot headers have no threshold check (by definition); TPraos forging not implemented (H3)
