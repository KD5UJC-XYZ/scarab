<!-- SPDX-License-Identifier: Apache-2.0 -->
# Persistence: the Leios build's `state` layout

Part of the [compatibility matrix](../compatibility.md); the register entry is `persistence-leios-state-layout` in [compatibility.json](../data/compatibility.json).

## Status as recorded

partial: the `state` file of the build `--reference-build leios-b004fe0c` names (cardano-node b004fe0c, cardano-ledger 9df9f98c, ouroboros-consensus 2416493b) is read and written in every Shelley-based era up to Conway (#826 step b). It differs from the 11.1 layout in every such era: the extended state has the Peras state third, read with the default when it has two parts and always written with three (`Ledger/Extended.hs:420-505`); a hard-fork bound has the next Peras round fourth, read as zero from three items and always written with four (`HardFork/History/Summary.hs:573-601`); the Shelley ledger state's fourth field is the cumulative transaction bytes (`Shelley/Ledger/Ledger.hs:885-922`); the `NewEpochState` has no pool distribution (`Shelley/LedgerState/Types.hs:393-422`); the mark, set and go snapshots are wrapped with the mark's epoch, committee size and key age and the set's Leios committee (`State/SnapShots.hs:386-594`); and a pool's state and its stake snapshot have its voting key after the VRF key hash (`State/StakePool.hs:165-266`, `State/SnapShots.hs:280-315`). Each of these records holds a part per layout (a closed enum per `StateLayout`), and each layout's codec writes only its own: a value of one layout is refused by the other's writer, and nothing is converted. `import-ledger-snapshot` and `export-ledger-snapshot` take the build; `follow` refuses it (`ReferenceBuildNotYet.Rules`) until its rules are implemented (step c).

## Evidence

`LeiosLayoutSuite` (storage): property laws of each record of the Leios layout and of a whole `state` file in every era from Shelley to Conway, with valid BLS12-381 voting keys; the refusal of each layout's values by the other's writer, naming where; hand-derived bytes of the bound, the Peras state (this build's constant `82 83 01 80 80 80`, an error state, and an epoch context refused as the reference refuses it before Dijkstra), a voting key that is not a point, and a chunked byte string below version 12; and the captured states of `fixtures/leios/state`: the last Conway state (slot 195) read and written byte for byte and refused by the 11.1 layout's two-part check (82ecba32 `Ledger/Extended.hs:268-280`), a two-part form of it read with the default Peras state and written as captured, and the first Dijkstra state (slot 203) refused at its telescope with its Peras state and the bounds of its past eras read and written byte for byte. `LeiosLayoutImportSuite` (node): slot 195 imported under `leios-b004fe0c` and exported with `state`, `tables` and `meta` byte for byte, an export under `11.1` refused, and the import under `11.1` refused with `Error.State` and nothing published. `ReferenceBuildSuite` and `ReferenceBuildStartSuite`: both layouts have a codec, and `follow` refuses the Leios build at start. `LedgerFileSuite` and `HaskellStateCodecSuite` keep the 11.1 layout's laws and fixtures. The `leios-state`, `leios-peras-state` and `leios-bound` fuzz targets.

Consensus framing is checked in both layouts: `LeiosLayoutSuite` mutates each shared envelope,
including the stable Praos and TPraos state, to an indefinite array and requires refusal. The
stable reference uses `decodeListLenOf`/`decodeListLen`/`enforceSize` (ouroboros-consensus
`82ecba32`, `Ledger/Extended.hs:277`, `HardFork/History/Summary.hs:541`,
`Util/Versioned.hs:176`). Positive controls keep indefinite ledger records accepted; ledger
`decodeRecordNamedT` permits those (cardano-ledger `f649f975`, `Binary/Decoding/Decoder.hs:700-708`).
The Peras framing mutations apply only to Leios, whose extended state includes that component.

## Explicitly unchecked or unconfirmed

The Dijkstra state in this layout is not read (`DijkstraNotYet.LedgerState`, #54, #826 step d), and an epoch context of the Peras state with a Dijkstra voting committee with it. The rules of the build are not selected by it yet (step c): the rules this build runs produce values of the 11.1 layout, and a Leios state is not followed. The pool distribution of a Leios `NewEpochState` is derived from the set snapshot without the pools' voting keys, which the reference's `IndividualPoolStake` holds (`State/PoolDistr.hs:73`). The layout was diffed against the pinned sources era by era; only Conway is checked on reference bytes.
