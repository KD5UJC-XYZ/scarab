<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: Alonzo and Babbage

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-alonzo-babbage` in [compatibility.json](../data/compatibility.json). The sections below record the implementation, evidence and remaining limits.

## Status as recorded

implemented incl. collateral, inline datums, reference inputs and scripts; Babbage checks the collateral count without redeemers and Alonzo does not check it, as the reference (`Babbage/Rules/Utxo.hs:412`; `Alonzo/Rules/Utxo.hs:570-572` has no such test, cardano-ledger f649f975; #412), and bootstrap output attributes/network are checked under either validity flag (#178); UTXO records every failing predicate in the era's order (#412); the payment key (or Byron root) of every collateral input is a needed key witness whether or not the transaction has redeemers, in every era from Alonzo (`getShelleyWitsVKeyNeededNoGov` over `spendableInputsTxBodyF`, cardano-ledger f649f975 `Shelley/UTxO.hs`, `Alonzo/TxBody.hs`, `Babbage/TxBody.hs`, `Conway/TxBody.hs`; #331); BBODY checks the body, LEDGERS and then the execution units of the whole block, as `alonzoBbodyTransition` does (`Alonzo/Rules/Bbody.hs:149-167,186-214`, #412); the script integrity hash has a language view for every Plutus language the transaction needs, with `null` for the cost model of a language the parameters have none for (`41 f6` for PlutusV1, `f6` for V2 and V3; `getLanguageView`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/PParams.hs:566-584`, cardano-ledger f649f975; #344), so such a transaction passes UTXOW and fails collection with `NoCostModel`

## Evidence

`CollateralWitnessSuite` (protocols 5 to 11: a collateral key missing from the signers is `MissingVKeyWitnesses` with no redeemers; signed, and no-collateral, controls), `PreConwayUtxoPredicatesSuite` (both validity flags, 64/65-byte attribute payloads, output networks, reference output-predicate order, collateral returns and decoded-set collateral accounting), `ShelleyLedgerSuite` (signed LEDGER refusal and accepted boundary controls), `ShelleyInstantStakeReferenceSuite` (32 results of the released `addShelleyInstantStake`/`deleteShelleyInstantStake` for the credential and pointer maps under the Shelley and Babbage `TxOut`: Word64 wrap, missing-entry insertion, zero removal, add-before-delete, `TxIn` order; [scope and pins](../../fixtures/reference/instant-stake/README.md), #299); 32 original bodies measured against 128 unchanged released Haskell predicate calls (including direct Conway collateral API checks) ([scope and pins](../../fixtures/reference/pre-conway-utxo/README.md)); unit/property; devnet e2e (8 + 8 comparisons, matched); Preview Alonzo/Babbage sync (whole chain, no rule failure); `ScriptIntegritySuite` (null language views and their hashes, computed outside Scarab from the reference's encoding) and `ConwayRulesSuite` (a body hashed without the language is `PPViewHashesDontMatch`, one hashed with the null view is `CollectErrors` `NoCostModel`; #344); empty-redeemers script data hash on real Preview blocks (`PreviewEmptyRedeemersSuite`: Babbage `0x80`, redeemers key absent and explicit `0x80`; Conway protocols 9 to 12 `0xa0`; B6)

`ValueNotConservedUTxO` counts minted tokens as consumed and burned ones as produced, as the reference does (`getConsumedMaryValue` and `burnedMultiAssets`, cardano-ledger f649f975 `Mary/UTxO.hs:72-97`; #828, `ValueConservationSuite`). The verdict was already the reference's; the failure's payload now is too.

Each era's UTXO runs its own checks whatever the protocol version (cardano-ledger f649f975; #867, `EraNotVersionUtxoSuite`, through `ShelleyUtxo.validate`): Alonzo at version 4 takes its minimum from `coinsPerUTxOWord` (`Alonzo/TxOut.hs:370-372`) and limits values by `maxValSize`, the last output first (`Alonzo/Rules/Utxo.hs:400-424`); Babbage at version 6 takes its minimum from `coinsPerUTxOByte` with `BabbageOutputTooSmallUTxO` (`Babbage/TxOut.hs:230`, `Babbage/Rules/Utxo.hs:302-321`) and takes the collateral return off the collateral (`Babbage/Rules/Utxo.hs:170-192`).

UTXOW also follows the era whatever the protocol version (#880, `EraNotVersionUtxowSuite`, through each era's LEDGER): Alonzo at version 4 permits datums and reads its tag-259 auxiliary data; Alonzo at version 7 checks every supplied native script before the missing-script check (`Alonzo/Rules/Utxow.hs:324-372` at cardano-ledger f649f975). Babbage at version 6 includes reference inputs and the collateral return among supplemental datum hashes (`Babbage/UTxO.hs:74-84`) and checks malformed reference scripts (`Babbage/Rules/Utxow.hs:379`). Absent redeemers use the era's `eraProtVerLow`, so Babbage at version 9 still hashes `0x80` (`Alonzo/TxWits.hs:157-162,203-211,227-230`, `MemoBytes/Internal.hs:294-295`). `ScriptIntegritySuite` checks the unchanged preimage bytes in every era at its usual versions; `PreviewEmptyRedeemersSuite` preserves the real Babbage and Conway transaction hashes even when only their protocol version is changed. The script decoder still receives the protocol version; the mismatch failure changes at version 11 as the reference does (`Alonzo/Rules/Utxow.hs:282-286`).

UTXOS and the block's execution units run in every era from Alonzo whatever the protocol version (cardano-ledger f649f975): Alonzo at version 4 evaluates the scripts against the `is_valid` flag and takes the collateral of a transaction that fails phase 2 (`Alonzo/Rules/Utxos.hs:159-238`, `Alonzo/Rules/Utxo.hs:775-784`, `Babbage/Rules/Utxo.hs:474-485`; #881, `EraNotVersionUtxosSuite`, through `ShelleyUtxo.sts`), and limits the execution units of the block (`validateExUnits`, `Alonzo/Rules/Bbody.hs:149-167,212`; #882, `ShelleyBbodySuite`), which Shelley's BBODY does not, at any version.

The slot of no return (`getTheSlotOfNoReturn`, cardano-ledger f649f975 `Slot.hs:106-114`), which TICK's solidifying of the future parameters and the PPUP too-late check use, is computed exactly (`EpochGeometrySuite`). The reference's `Word64` doubling of the stability window wraps for a window of 2^63 slots or more, which Scarab does not reproduce (#825): an approved deviation ([owner decision](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6747536849)).

The script-context translator takes the ledger era through UTXOS and `Phase2` (#883): Alonzo's
Byron-output omission, Babbage's refusal, its V1 reference-field restrictions and each era's validity
interval are selected by era (`Alonzo/Plutus/TxInfo.hs:130-149,252-263`, `Babbage/TxInfo.hs:119-154,319-370`,
cardano-ledger f649f975). `ScriptContextEraSuite` exercises UTXOS at mismatched versions;
`ScriptContextUsualVersionSuite` preserves complete CBOR bytes at each usual version.

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

block execution-unit limits are validated (protocol >= 5) but only by unit tests (`ShelleyBbodySuite`, `BbodySuite`) and the sync; no Alonzo-era transaction with datums and no redeemers exists on Preview, so that case of the integrity hash (`0x80` redeemers) has no chain evidence of its own (the Babbage case is the same code)
