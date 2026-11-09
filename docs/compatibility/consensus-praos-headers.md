<!-- SPDX-License-Identifier: Apache-2.0 -->
# Consensus: Praos headers (Babbage, Conway)

Part of the [compatibility matrix](../compatibility.md); the register entry is `consensus-praos-headers` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: VRF, KES, opcert counters, nonce evolution, leader threshold; typed Praos and threshold causes retained through header/block validation and forging (M11-E/#82); checked before the block is fetched against a ledger view forecast within the stability window (G6)

## Evidence

unit/property; golden Praos/VRF/KES/opcert vectors; devnet nonce comparison (all five Praos nonces); Preview sync (every header threshold-checked); leader-threshold reference corpus `fixtures/reference/leader-threshold` (490 cases; `ln(1 - f)` from the reference's own `splitLn` for any active-slot coefficient in (0, 1], verdicts on both sides of the threshold for 256- and 512-bit leader values; #770)

## Explicitly unchecked or unconfirmed

header protocol-version rule on mainnet (`HeaderProtVerTooHigh`) implemented from source and never exercised (no mainnet run); Byron to Shelley derived from the Byron update state (B13, C1); the corpus's verdicts come from a copy of `checkLeaderNatValue`'s body in the oracle harness, because `cardano-protocol` is not in the reference image (the `splitLn`, `taylorExpCmp` and `mkActiveSlotCoeff` it calls are the installed library's); `MaxReached` of `taylorExpCmp` is not exercised (with stake at most 1 the comparison decides first)

## KES arithmetic boundaries

The shared TPraos/Praos `OCertRules` checks the KES window with exact arithmetic. An end at or above
2^64 is refused: the reference's Word64 addition wraps below the start, making its two window
inequalities unsatisfiable (cardano-ledger `f649f975`, `TPraos/Rules/OCert.hs:84-90`). This preserves
the rejection rather than accepting an exact window that the reference rejects. `ProviderDefectSuite` checks the largest representable end and the first
overflowing end. Ordinary windows and the cold-key-before-KES check order are unchanged.
