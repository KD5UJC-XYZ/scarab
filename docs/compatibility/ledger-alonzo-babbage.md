<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: Alonzo and Babbage

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-alonzo-babbage` in [compatibility.json](../data/compatibility.json). The sections below record the implementation, evidence and remaining limits.

## Status as recorded

implemented incl. collateral, inline datums, reference inputs and scripts; Babbage checks the collateral count without redeemers and Alonzo does not check it, as the reference (`Babbage/Rules/Utxo.hs:412`; `Alonzo/Rules/Utxo.hs:570-572` has no such test, cardano-ledger f649f975; #412), and bootstrap output attributes/network are checked under either validity flag (#178); UTXO records every failing predicate in the era's order (#412); the payment key (or Byron root) of every collateral input is a needed key witness whether or not the transaction has redeemers, in every era from Alonzo (`getShelleyWitsVKeyNeededNoGov` over `spendableInputsTxBodyF`, cardano-ledger f649f975 `Shelley/UTxO.hs`, `Alonzo/TxBody.hs`, `Babbage/TxBody.hs`, `Conway/TxBody.hs`; #331); BBODY checks the body, LEDGERS and then the execution units of the whole block, as `alonzoBbodyTransition` does (`Alonzo/Rules/Bbody.hs:149-167,186-214`, #412)

## Evidence

`CollateralWitnessSuite` (protocols 5 to 11: a collateral key missing from the signers is `MissingVKeyWitnesses` with no redeemers; signed, and no-collateral, controls), `PreConwayUtxoPredicatesSuite` (both validity flags, 64/65-byte attribute payloads, output networks, reference output-predicate order, collateral returns and decoded-set collateral accounting), `ShelleyLedgerSuite` (signed LEDGER refusal and accepted boundary controls), `ShelleyInstantStakeReferenceSuite` (32 results of the released `addShelleyInstantStake`/`deleteShelleyInstantStake` for the credential and pointer maps under the Shelley and Babbage `TxOut`: Word64 wrap, missing-entry insertion, zero removal, add-before-delete, `TxIn` order; [scope and pins](../../fixtures/reference/instant-stake/README.md), #299); 32 original bodies measured against 128 unchanged released Haskell predicate calls (including direct Conway collateral API checks) ([scope and pins](../../fixtures/reference/pre-conway-utxo/README.md)); unit/property; devnet e2e (8 + 8 comparisons, matched); Preview Alonzo/Babbage sync (whole chain, no rule failure); empty-redeemers script data hash on real Preview blocks (`PreviewEmptyRedeemersSuite`: Babbage `0x80`, redeemers key absent and explicit `0x80`; Conway protocols 9 to 12 `0xa0`; B6)

## Explicitly unchecked or unconfirmed

block execution-unit limits are validated (protocol >= 5) but only by unit tests (`ShelleyBbodySuite`, `BbodySuite`) and the sync; no Alonzo-era transaction with datums and no redeemers exists on Preview, so that case of the integrity hash (`0x80` redeemers) has no chain evidence of its own (the Babbage case is the same code)
