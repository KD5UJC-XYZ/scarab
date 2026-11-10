<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: Conway

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-conway` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented incl. scripts, all certificates, treasury donation, the protocol-11 pool rule (VRF key-hash uniqueness); BBODY in the reference's order: the reference scripts of the block, the body, LEDGERS, then the execution units of the whole block (`Conway/Rules/Bbody.hs:268,314`, `Alonzo/Rules/Bbody.hs:149-167,186-214`, #412)

## Evidence

unit/property; devnet soak (24/24, 41/41 and, with the extended governance load, 44/44) and e2e (Conway 30 comparisons, matched); Preview sync from epoch 646 (Conway, protocols 9 to 11); protocol-11 rules checked against the reference's refusals: 5 taken-VRF registrations refused with `VRFKeyHashAlreadyRegistered` and 5 around them accepted, a reference input that is also an input refused with `BabbageNonDisjointRefInputs` at 10, accepted without scripts at 11 and refused for a Plutus V3 script with `CollectErrors (BadTranslation ReferenceInputsNotDisjointFromInputs)`; the refused bytes replay through our rules (`DevnetRefusedTxSuite`)

`ValueNotConservedUTxO` counts minted tokens as consumed and burned ones as produced, as the reference does (`getConsumedMaryValue` and `burnedMultiAssets`, cardano-ledger f649f975 `Mary/UTxO.hs:72-97`; #828, `ValueConservationSuite`). The verdict was already the reference's; the failure's payload now is too.

The script integrity hash uses Conway's empty redeemers map even at protocol version 8 (#880, `EraNotVersionUtxowSuite`, through Conway LEDGER): absent redeemers are memoized at the era's `eraProtVerLow` (`Alonzo/TxWits.hs:157-162,203-211,227-230`, `MemoBytes/Internal.hs:294-295`, cardano-ledger f649f975). `ScriptIntegritySuite` checks unchanged preimage bytes at Conway's usual versions; `PreviewEmptyRedeemersSuite` checks real Preview transaction hashes by their block era, independently of the protocol version supplied to the rule. The version-11 mismatch failure remains a version test (`Alonzo/Rules/Utxow.hs:282-286`).

Conway's BBODY limits the reference scripts of the block whatever the protocol version (`validateBodyRefScriptsSizeTooBig`, `Conway/Rules/Bbody.hs:314,330-355`; #882, `BbodySuite` at versions 7 and 8), and Conway's LEDGER skips `validateWithdrawalsDelegated` only in the bootstrap phase, protocol version 9 (`hardforkConwayBootstrapPhase`, `Conway/Era.hs:257-258`, `Conway/Rules/Ledger.hs:379-380`; #885, `ConwayRulesSuite` at version 8). A chain that starts Conway by `TestConwayHardForkAtEpoch` keeps the version of Babbage.

The slot of no return (`getTheSlotOfNoReturn`, cardano-ledger f649f975 `Slot.hs:106-114`), which TICK's solidifying of the future parameters uses, is computed exactly (`EpochGeometrySuite`). The reference's `Word64` doubling of the stability window wraps for a window of 2^63 slots or more, which Scarab does not reproduce (#825): an approved deviation ([owner decision](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6747536849)).

## Explicitly unchecked or unconfirmed

native pool rules maintain the reference-counted VRF registry, and the protocol-11 transition populates it. Fresh native genesis retains injected pools with an empty registry, as the reference does; previously written or imported state still needs its own provenance checks ([#129](https://github.com/KD5UJC-XYZ/scarab/issues/129)); Plutus V3 reference-input refusal only replayed in a unit test, not from the refused bytes; see governance row
