<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: Shelley to Mary

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-shelley-to-mary` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: UTXOW/UTXO/DELEGS/PPUP, MIR, rewards, epoch boundary; pre-Allegra reward choice, reward prefilter, MIR rules by protocol version, Mary scaled minimum, 4000-byte value limit cross-checked against the ledger source (B7, B8, B12)

## Evidence

unit/property (`RewardsSuite`, `PreConwayRulesSuite`); devnet e2e with three Shelley epochs and boundary load, per-era comparison (Shelley 12, Allegra 4, Mary 4, all matched), including pre-Allegra rewards (two accounts with several rewards, 910,408,206 lovelace ignored), MIR early/mid/late, refused outputs and MIR variants; era fixtures

`ValueNotConservedUTxO` counts minted tokens as consumed and burned ones as produced, as the reference does (`getConsumedMaryValue` and `burnedMultiAssets`, cardano-ledger f649f975 `Mary/UTxO.hs:72-97`; #828, `ValueConservationSuite`). The verdict was already the reference's; the failure's payload now is too.

Each era's UTXO runs its own checks whatever the protocol version, as the reference's rule of each era does (cardano-ledger f649f975; #867, `EraNotVersionUtxoSuite`, through `ShelleyUtxo.validate`): Shelley's inclusive time to live at version 3, Allegra's validity interval with its exclusive upper bound at version 2 (`Shelley/Rules/Utxo.hs:414-423`, `Allegra/Rules/Utxo.hs:176,225-235`), Mary's 4000-byte value limit at version 2 and its order of the outputs at version 5 (`Allegra/Rules/Utxo.hs:211,237-254`), and Mary's minimum, which never finds an output with assets too small, at version 2 (`Mary/TxOut.hs:33`). A chain that starts an era by `Test<Era>HardForkAtEpoch` keeps the version of the era before it; the UTXOW, UTXOS, BBODY, script-context and parameter sites of the same kind follow the era too (#880 to #884).

The slot of no return (`getTheSlotOfNoReturn`, cardano-ledger f649f975 `Slot.hs:106-114`), which TICK's solidifying of the future parameters and the PPUP too-late check use, is computed exactly (`EpochGeometrySuite`). The reference's `Word64` doubling of the stability window wraps for a window of 2^63 slots or more, which Scarab does not reproduce (#825): an approved deviation ([owner decision](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6747536849)).

Protocol-parameter updates and the decentralisation view follow the era, whatever its protocol
version (cardano-ledger f649f975; #884). PPUP and UPEC apply key 17 as Alonzo's
`coinsPerUTxOWord` (`Alonzo/PParams.hs:658-664`) or Babbage's `coinsPerUTxOByte`
(`Babbage/PParams.hs:405-412`); the conversion is only the Babbage translation, including the
future parameters (`Babbage/Translation.hs:84-91`, `Babbage/PParams.hs:316-345,389-391`).
`PreConwayRulesSuite` exercises a Babbage update at version 6, its adoption without a second
conversion, an Alonzo update at version 7, and the subsequent conversion at the era boundary.
The era's `ppDG` is `d` through Alonzo (`Alonzo/PParams.hs:338`) and zero from Babbage
(`Babbage/PParams.hs:198`): `ShelleyBbodySuite` checks the blocks counted in Alonzo at version 7
and Babbage at version 6, and `EraTransitionSuite` checks both production TPraos header paths at
Alonzo version 7. The parameter record and snapshot schema are unchanged.

Dated evidence (2026-11-10, #884): the original Alonzo-to-Babbage translation kept both raw
proposal maps unchanged, whereas the reference upgrades them (`Babbage/Translation.hs:155-168`).
The owner approved including this resolved difference in #884 and explicitly recording it
([approval record](https://github.com/KD5UJC-XYZ/scarab/issues/884#issuecomment-6774945758)).
`ProtocolParamProposalTranslationGapSuite` reproduces it through signed early/late PPUP votes,
the translation and `Hfc.tick`, both at an epoch-triggered Babbage version 6 and the ordinary
version-6-to-7 transition. It converts the stored key-17 values 40003 and 48003 into 5000 and
6000, removes the obsolete fields, preserves untouched fields' original bytes and leaves an
unread legacy key 17 unadoptable. This establishes a valid ordinary-version scenario, not an
observed occurrence on an actual public chain. No retained reference divergence is approved;
the pending proposals' replacement values are the era translation itself, with no schema change.

## Explicitly unchecked or unconfirmed

devnet only: no Shelley to Mary mainnet blocks are replayed (Preview starts in Alonzo); the Allegra/Mary minimum-output test is lenient for values with assets exactly as the 11.1.3 reference is (it differs from the specification text); small comparison counts per era
