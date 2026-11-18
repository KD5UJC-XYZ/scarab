<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra translation: the ledger state

One case measured on 2026-11-17 against the unchanged Haskell ledger of the public Leios testnet build
(cardano-node `b004fe0c`, cardano-ledger `9df9f98c`, cardano-ledger-dijkstra 0.5.0.0), for
[#826](https://github.com/KD5UJC-XYZ/scarab/issues/826). The oracle source and
[recipe](../../../scripts/oracle/dijkstra-state-translation/README.md) are committed; `provenance.json`
records the image identities, the program's hash, the SHA-256 of each input and of each ledger source the
result depends on, as found in the image and checked against the pin.

## The case

`cases.jsonl` holds one line, `conway-195`: the ledger state of the
[Leios build's snapshot](../../../fixtures/leios/state/README.md) after slot 195, the last Conway block
before Dijkstra starts at slot 200, with the devnet's `genesis/dijkstra-genesis.json` as context.

| field | contents |
|---|---|
| `stateSha256`, `tablesSha256`, `dijkstraGenesisSha256` | the inputs |
| `newEpochStateSpan`, `newEpochStateSha256` | where the Conway `NewEpochState` lies in `state` (bytes 119 to 12,249, 12,130 bytes) and its digest |
| `reference.conwayVersion`, `reference.dijkstraVersion` | 9 and 12, the versions the state is read and written at |
| `reference.conwayRoundTrip` | the decoded Conway state encodes back to the snapshot's bytes |
| `reference.dijkstraNewEpochState` | the translated state's CBOR (15,335 bytes), hex |
| `reference.dijkstraRoundTrip` | Dijkstra's decoder reads it back to the same bytes |
| `reference.outputs` | the 7 outputs of the snapshot's UTxO table, in key order: the key (`txIn`, the consensus layer's 34-byte `BigEndianTxIn`), the Conway output's `MemPack` bytes, and the upgraded Dijkstra output as CBOR at version 12 and as `MemPack` |

The state's own UTxO is empty, as in every UTxO-HD snapshot (`state` holds no outputs; `tables` holds
them), so the translated state's UTxO is empty too and the outputs are recorded beside it. Translating a
state whose UTxO held them would give the same: the UTxO's translation is `upgradeTxOut` on each output
(`Dijkstra/Translation.hs:225-227`). Every output's `MemPack` is unchanged by the upgrade, which agrees
with `dijkstra-203/tables` being byte-identical to `conway-195/tables`.

## What the reference does at the crossing

The hard-fork combinator crosses into Dijkstra when it ticks the last Conway state past the era's end
(`extendToSlot`, ouroboros-consensus `2416493b`
`ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/HardFork/Combinator/State.hs:219-327`).
`howExtend` (`:289-329`) translates the state with its tables forgotten (`:305-310`), translates the
pending table differences (`:315-326`), and only then does Dijkstra's tick run. So the input is the
unticked last Conway state, and the output is **not** `dijkstra-203/state`, which is after Dijkstra's
`TICK` into epoch 1 and the block of slot 203. Of the two, the current protocol parameters agree byte
for byte (Dijkstra's epoch boundary does not change them here), which shows the context is the one the
node used; the previous parameters, the snapshots, the reward update and the pools do not, as the tick
changes them.

The ledger part, which this oracle runs (cardano-ledger `9df9f98c`,
`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Translation.hs`):

- `NewEpochState` (`:70-80`): the epoch, both block counts and the reward update carried; the epoch state
  translated; the stashed AVVM addresses `()`.
- `EpochState` (`:99-107`): the chain account state and the non-myopic state carried; the snapshots
  carried (`:96-97`); the ledger state translated.
- `LedgerState` and `CertState` (`:121-138`): `DState` with its accounts coerced (`:109-111`); `PState`
  coerced and its VRF key hash counts recomputed (`populateVRFKeyHashes`, `:113-116`;
  `libs/cardano-ledger-core/src/Cardano/Ledger/State/CertState.hs:528-539`); `VState` coerced (`:118-119`).
- `UTxOState` (`:213-227`): deposits, fees, instant stake and donation carried; the UTxO upgraded output by
  output; the governance state translated.
- `ConwayGovState` (`:200-211`): committee and constitution coerced; the proposals translated
  (`translateProposals`, `Conway/Governance/Proposals.hs:246-251`, each proposal's action upgraded,
  `:140-160`); the current, previous and future parameters upgraded with the genesis's
  `dgUpgradePParams` (`:86-94`); the DRep pulser finished (`finishDRepPulser`,
  `Conway/Governance/DRepPulser.hs:388-393`) and its snapshot and ratify state translated (`:162-198`), the
  enact state's two parameter sets upgraded (`:172-183`). A stored pulser is always complete (its encoder
  finishes it, `DRepPulser.hs:468-472`), so finishing it changes nothing here.

The consensus part, out of scope (consensus is not compiled in the image, since it needs native libraries
the base image lacks; see the [Leios testnet harness](../../../scripts/oracle/leios-testnet/README.md)):

- the Shelley ledger state around the `NewEpochState`
  (`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/ShelleyHFC.hs:391-415`): the tip
  carried (`:386-389`), the transition counter `shelleyAfterVoting` reset to 0 (`:412`; it is 30 in
  `conway-195` and 0 in `dijkstra-203`), the cumulative transaction bytes carried, and the tables retyped;
  before that the protocol index is retyped from Praos to Praos2 (`transLeiosLS`,
  `ouroboros-consensus-cardano/src/ouroboros-consensus-cardano/Ouroboros/Consensus/Cardano/CanHardFork.hs:742-755`),
  and no new table differences are produced (`noNewTickingDiffs`, `:734`);
- the UTxO table: the crossing does not rewrite it. Its outputs stay Conway outputs and are upgraded with
  `upgradeTxOut` when they are read into Dijkstra (`ejectHardForkTxOut`,
  `ouroboros-consensus-cardano/src/ouroboros-consensus-cardano/Ouroboros/Consensus/Cardano/Ledger.hs:150-162`,
  through `translateTxOutWith`, `CanHardFork.hs:757-765`), while a snapshot taken in Dijkstra writes the
  same `MemPack` bytes (`Cardano/Ledger.hs:188-212`) and reads them as Dijkstra outputs (`:214-257`). This
  oracle records the upgrade of each output, not the table's life;
- the telescope: Conway's state becomes a past era with its bound, Dijkstra's the current one
  (`State.hs:296-304`);
- the chain-dependent state, Praos to Praos2 (`translateChainDepStateAcrossShelley`, `ShelleyHFC.hs:304-318`;
  `TranslateProto (Praos c) (Praos2 c)`,
  `ouroboros-consensus-protocol/src/ouroboros-consensus-protocol/Ouroboros/Consensus/Protocol/Praos2.hs:189-200`:
  every field carried, the Leios announcement empty);
- the forecast across the boundary, which translates the state the same way and forecasts with Dijkstra's
  rules (`CanHardFork.hs:773-808`);
- the header state's telescope and the Peras state of the extended state.

## Scope

One state of one devnet, in which nothing is pending: no proposals, no future parameters, no pool
re-registrations, and pools without voting keys. The translation's code paths for those are not exercised
by this case. What it does change here: the parameters (31 fields in Conway, upgraded to Dijkstra's with
the genesis's values), the VRF key hash counts (empty in the Conway state, one for each of the three
pools after the translation), and the forms each era's encoder writes. The result is the ledger's, not the consensus layer's whole extended state at the crossing;
a comparison with Scarab's `Translate.conwayToDijkstra` compares the `NewEpochState` and, separately, the
outputs.

## Provenance and licence

Generated by this project from the [Leios build state files](../../../fixtures/leios/state/README.md), a
throwaway local cluster of the published Leios testnet image (input-output-hk/ouroboros-leios,
Apache-2.0, not redistributed), by the unchanged cardano-ledger (Apache-2.0). No key or secret is
contained.
