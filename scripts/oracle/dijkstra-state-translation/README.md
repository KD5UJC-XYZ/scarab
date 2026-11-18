<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra translation: the ledger state

**Status: Current** — optional offline reference oracle for [#826](https://github.com/KD5UJC-XYZ/scarab/issues/826).

Build the [Leios testnet oracle image](../leios-testnet/README.md) first, then:

```sh
docker build --label xyz.kd5ujc.scarab.kind=oracle \
  -f scripts/oracle/dijkstra-state-translation/Dockerfile -t scarab-dijkstra-state-translation-oracle:local .
python3 scripts/oracle/dijkstra-state-translation/run.py            # offline: inputs, program and fixture match the provenance
python3 scripts/oracle/dijkstra-state-translation/run.py --replay   # run the image (no network, read-only) and compare
python3 scripts/oracle/dijkstra-state-translation/run.py --record   # rewrite the fixture and its provenance
```

The input is the [Leios build's snapshot](../../../fixtures/leios/state/README.md) `conway-195`, the
extended ledger state after the last Conway block of a local devnet, and the devnet's Dijkstra genesis
file. `run.py` takes the ledger's `NewEpochState` out of the snapshot (the extended state
`[1, [ledger, header, Peras state]]`, the telescope's seventh era, `encodeShelleyLedgerState`'s
`[2, [tip, NewEpochState, transition, cumulative transaction bytes]]`, ouroboros-consensus `2416493b`
`Shelley/Ledger/Ledger.hs:885-903`) and checks that the genesis file is the one the configuration names
(`DijkstraGenesisHash`). `Main.hs` then runs only unchanged cardano-ledger `9df9f98c` code, the revision
cardano-node `b004fe0c` pins:

- it reads the state with `fromCBOR` (Conway's `fromEraCBOR`, at version 9), as consensus does
  (`Shelley/Ledger/Ledger.hs:918`), and refuses to go on unless `toCBOR` gives the snapshot's bytes back;
- it reads the Dijkstra genesis with the ledger's `FromJSON DijkstraGenesis` (`Dijkstra/Genesis.hs:32-35`),
  the translation context (`Dijkstra/Translation.hs:51`) the node's Dijkstra ledger configuration holds
  (`tcTranslationContextL`, `Dijkstra/Transition.hs:67-68`; consensus `Cardano/Node.hs:1026-1041`);
- it applies `translateEra'` of `TranslateEra DijkstraEra NewEpochState` (`Dijkstra/Translation.hs:70-80`),
  as the crossing does (consensus `Cardano/CanHardFork.hs:730-741`), and writes the result with `toCBOR`
  (Dijkstra's `toEraCBOR`, at version 12), checking that Dijkstra's decoder reads it back to the same bytes;
- it reads the snapshot's UTxO table as the in-memory backend does at a Conway tip (`valuesMKDecoder`,
  `Ledger/Tables.hs:286-292`, then `getOne`, `Cardano/Ledger.hs:240-257`: `decShareCBOR` with the interns
  of the state's accounts, at Conway's version). These two decoder lines are the consensus layer's, written
  out over the ledger's calls as the [Leios testnet harness](../leios-testnet/README.md) does for blocks;
  the keys are kept as bytes, since the crossing carries them unchanged (`translateTxInWith = coerce`).
  Each output is then upgraded with Dijkstra's `upgradeTxOut` (`Dijkstra/TxOut.hs:33`), the crossing's
  `translateTxOutWith` (`Cardano/CanHardFork.hs:757-765`).

No ledger code is patched and none is missing from the image: `FromJSON DijkstraGenesis` and every
decoder used are the build's own. `run.py` checks in the image that the ledger sources the result depends
on are those of `9df9f98c`. The [fixture](../../../fixtures/reference/dijkstra-state-translation/README.md)
records the result, the pins, what the crossing does besides, and the scope. No public-network
connection, node or key is needed.
