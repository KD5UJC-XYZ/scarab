<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: Shelley to Mary

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-shelley-to-mary` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: UTXOW/UTXO/DELEGS/PPUP, MIR, rewards, epoch boundary; pre-Allegra reward choice, reward prefilter, MIR rules by protocol version, Mary scaled minimum, 4000-byte value limit cross-checked against the ledger source (B7, B8, B12)

## Evidence

unit/property (`RewardsSuite`, `PreConwayRulesSuite`); devnet e2e with three Shelley epochs and boundary load, per-era comparison (Shelley 12, Allegra 4, Mary 4, all matched), including pre-Allegra rewards (two accounts with several rewards, 910,408,206 lovelace ignored), MIR early/mid/late, refused outputs and MIR variants; era fixtures

## Explicitly unchecked or unconfirmed

devnet only: no Shelley to Mary mainnet blocks are replayed (Preview starts in Alonzo); the Allegra/Mary minimum-output test is lenient for values with assets exactly as the 11.1.3 reference is (it differs from the specification text); small comparison counts per era
