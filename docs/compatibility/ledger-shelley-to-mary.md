<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: Shelley to Mary

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-shelley-to-mary` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: UTXOW/UTXO/DELEGS/PPUP, MIR, rewards, epoch boundary; pre-Allegra reward choice, reward prefilter, MIR rules by protocol version, Mary scaled minimum, 4000-byte value limit cross-checked against the ledger source (B7, B8, B12)

## Evidence

unit/property (`RewardsSuite`, `PreConwayRulesSuite`); devnet e2e with three Shelley epochs and boundary load, per-era comparison (Shelley 12, Allegra 4, Mary 4, all matched), including pre-Allegra rewards (two accounts with several rewards, 910,408,206 lovelace ignored), MIR early/mid/late, refused outputs and MIR variants; era fixtures

`ValueNotConservedUTxO` counts minted tokens as consumed and burned ones as produced, as the reference does (`getConsumedMaryValue` and `burnedMultiAssets`, cardano-ledger f649f975 `Mary/UTxO.hs:72-97`; #828, `ValueConservationSuite`). The verdict was already the reference's; the failure's payload now is too.

The slot of no return (`getTheSlotOfNoReturn`, cardano-ledger f649f975 `Slot.hs:106-114`), which TICK's solidifying of the future parameters and the PPUP too-late check use, doubles the stability window as a `Word64`, as the reference does (#825, `EpochGeometrySuite`). Only a genesis whose stability window exceeds 2^63 slots sees the wrap.

## Explicitly unchecked or unconfirmed

devnet only: no Shelley to Mary mainnet blocks are replayed (Preview starts in Alonzo); the Allegra/Mary minimum-output test is lenient for values with assets exactly as the 11.1.3 reference is (it differs from the specification text); small comparison counts per era
