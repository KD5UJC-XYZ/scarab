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


## KES relative periods

The OCERT window uses the original full-width relative period before invoking KES. At verification,
`OCertRules` selects the same Sum6KES path as the deployed reference without narrowing that period
modulo 2^32. For any nonnegative period at least 63, the six choices at 32, 16, 8, 4, 2 and 1 all go
right, as they do for 63. The deployed SingleKES leaf ignores the remaining period. Thus selecting
63 preserves the hash calls, selected key and signature verification; it is not a new lifetime cap.
The window and cold-certificate checks retain their order, and signing/key-evolution limits remain
unchanged.

Source: cardano-base `060819b59c184b951a54e3c563304983c53a3eac`,
`Cardano/Crypto/KES/Sum.hs:190-197` and `Single.hs:104-106`; the same source blobs are in the Leios
pin `c1e1ed8d7ae39c111c73fc5f59ac37ce5933b83c`. The source's leaf assertion is omitted in the deployed
11.1.3 binary and the captured Leios library. Exact binary/image identities and the dated inspection
command are in the [capture provenance](../../fixtures/sum6/period-boundaries-provenance.json).
An assertion-enabled build is not covered by this equivalence.

[Native boundary cases](../../fixtures/sum6/README.md#native-relative-period-boundary-capture-2026-11-09)
cover both rejection of earlier-leaf signatures at large periods and final-leaf acceptance.
`ProviderDefectSuite` compares the original predicate verdicts; it does not establish a complete
header, modified-genesis startup or network verdict. With a configured lifetime at most 64,
relative periods above 63 cannot reach KES after window validation.
