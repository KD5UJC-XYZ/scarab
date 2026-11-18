<!-- SPDX-License-Identifier: Apache-2.0 -->
# Leios node-to-client queries: stake pool state and Leios committee

Three states measured on 2026-11-18 against the unchanged Haskell ledger of the public Leios testnet build
(cardano-node `b004fe0c`, cardano-ledger `9df9f98c`, cardano-ledger-api 1.16.0.0), for
[#826](https://github.com/KD5UJC-XYZ/scarab/issues/826). The oracle source and
[recipe](../../../scripts/oracle/leios-ntc-queries/README.md) are committed; `provenance.json` records the
image's immutable ID, the oracle build it accepted (the digests of the image's `Main.hs`, package file,
cabal project and executable, and the pinned checkouts found unchanged), the program's hash, and the
SHA-256 of each input and of each ledger source the answers depend on, as found in the image and checked
against the pin.

## The cases

`cases.jsonl` holds one line for each [Leios build state file](../../../fixtures/leios/state/README.md):

| line | era, version | pools | voting keys | delegators | committee seats (current, future) |
|---|---|---|---|---|---|
| `conway-195`, the devnet's last Conway state | Conway, 9 | 3 | 0 | 3 | 0, 0 |
| `dijkstra-203`, the devnet's first Dijkstra state | Dijkstra, 12 | 3 | 0 | 3 | 0, 3 (no keys) |
| `testnet-2168639`, the public Leios testnet at epoch 100 | Dijkstra, 12 | 154 | 154 | 312 | 154, 154 |

| field | contents |
|---|---|
| `name`, `era`, `stateSha256` | the input, and the era its telescope's length gives (seven eras for Conway, eight for Dijkstra) |
| `epoch` | the `NewEpochState`'s epoch, its first field: 0, 1 and 100 |
| `newEpochStateSpan`, `newEpochStateSha256` | where the `NewEpochState` lies in `state` and its digest (for `conway-195` the same span as [the state translation's](../dijkstra-state-translation/README.md)) |
| `reference.version`, `reference.roundTrip` | the version the state is read at and the answers are written at; the state encodes back to its own bytes |
| `reference.pools`, `poolsWithBlsKey`, `poolsRetiring`, `poolsWithFutureParams`, `poolsWithMetadata`, `delegators` | counts over the answer for every pool: which branches of the pool state's encoding the bytes exercise |
| `reference.currentCommitteeSeats`, `futureCommitteeSeats` | the seats of the committee answer's two committees |
| `reference.cases` | eight answers, in order: `GetStakePoolState` (`tag` 40) for every pool with delegators (`poolIds` null, `includeDelegators` true), for every pool without; for `poolIds` the two smallest registered pool ids and `ff…ff` (28 bytes, registered under no pool) without and with delegators; for the empty set with delegators; and `GetLeiosCommittee` (`tag` 41); then two answers on the derived state below. `result` is the answer's CBOR, hex |
| `reference.cases[6]`, `[7]` | `GetStakePoolState` on the derived state for `poolIds` the smallest registered pool id alone, with and then without delegators, each also with `derived` (`"retiring+future"`), `retiringEpoch` (`epoch` + 1) and `network` (`"Testnet"`) |

`result` is what consensus's `LC.toEraCBOR @era` writes for the answer (ouroboros-consensus `2416493b`
`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Query.hs:969-970`): the
ledger's `encCBOR` at the era's lowest version. It is the era-level result only, without the hard-fork
combinator's wrapping or the node-to-client message around it. Sizes, in bytes: the devnet states' pool
answers 466, 361, 241, 311 and 1 (identical in Conway and Dijkstra), their committees 4 and 25, the derived
answers 264 and 229; the testnet's 73,280, 62,834, 819, 921 and 1, its committee 37,242, the derived answers
888 and 821. The file is 367 kB.

## The derived state

No captured state has a retiring pool or future pool parameters, so `Main.hs` derives one state from each
with the ledger's own lenses and functions, nothing patched: with P the smallest registered pool id, the
`PState`'s `psRetiring` gets P ↦ the state's epoch + 1 (`psRetiringL`) and its `psFutureStakePoolParams`
gets P ↦ `stakePoolStateToStakePoolParams Testnet P (P's StakePoolState)` (`psFutureStakePoolParamsL`;
`libs/cardano-ledger-core/src/Cardano/Ledger/State/CertState.hs:490-497`,
`libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:325-343`). Both maps are empty in all three
states, so the derived maps hold P alone. The network is the one every input's Shelley genesis file names
(`networkId` `Testnet` in `fixtures/leios/state/genesis/shelley-genesis.json` and
`fixtures/leios/testnet/config/shelley-genesis.json`; `run.py` refuses another), which is what
`stakePoolStateToStakePoolParams` puts into the reward account. No ledger rule produces this state: it is a
state the encoding of the answer must handle, not one the chain reached. `run.py` checks that each derived
answer is P's entry of the plain answer (with or without delegators) in its first 12 fields, with
`retiringEpoch` as the retirement and future parameters present.

The future parameters are `EncCBOR (StakePoolParams era)` (`StakePool.hs:701-740`), whose length depends on
the version: 9 fields (operator, VRF hash, pledge, cost, margin, reward account as its 29 address bytes,
owners, relays, metadata or `null`), and at version 12 and above, when the pool has a voting key, 10, the
`BlsKey` `[public key, proof of possession]` (without the epoch it was registered in) after the VRF hash.
The devnet states give 9 (no voting keys; Conway at 9 and Dijkstra at 12 alike), the testnet 10.

## What the bytes show

From cardano-ledger `9df9f98c`, and checked on the recorded bytes:

- An answer of `GetStakePoolState` is a map from pool id to the 14-field list of `EncCBOR
  (QueryResultStakePoolState era)` (`libs/cardano-ledger-api/src/Cardano/Ledger/Api/State/Query.hs:570-587`),
  whose `StrictMaybe` fields (voting key, metadata, delegators, retirement, future parameters) are `null`
  when absent and the value itself when present (`encodeNullStrictMaybe`). The voting key is `BlsKeyState`,
  `[[public key (96 bytes), proof of possession (48 bytes)], epoch registered]`
  (`libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:532-536, 559-564`). The margin is a tagged
  (30) rational, the account id a credential, owners and delegators sets (tag 258).
- The map's length is definite up to 23 entries and indefinite above (`encodeMap`,
  `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Encoding/Encoder.hs:397-408, 433-443`): the testnet's
  answers for every pool start `bf`, the devnet ones `a3`, the restricted ones `a2`, the derived ones `a1`, the empty one `a0`.
- An answer of `GetLeiosCommittee` is `[current, future]` (`Query.hs:1008-1012`). Unlike the pool state's
  optional fields, the future committee is written with `encCBOR` of a `StrictMaybe`, a list of zero or one
  item (`EncCBOR.hs:315-316`), and `queryLeiosCommittee` always gives it (`SJust`, `Query.hs:1019-1026`), so
  in every answer here the second field starts with `81`. A committee is the list of its seats (`EncCBOR.hs:495-496`), definite up
  to 23 and indefinite above (`encodeList`, `Encoder.hs:499-509`; the testnet's current committee starts
  `9f`); a seat is `[weight, key]` (`EncCBOR.hs:487-491`), the weight a tagged (30) rational and the key a
  `StrictMaybe`, again a list of zero or one item. `conway-195` answers `82 80 81 80` (both committees empty);
  `dijkstra-203` has an empty current committee and a future one of three keyless seats of weight 1/3, since
  no pool there has a voting key.

## Scope

Exercised: pools with and without voting keys, with and without metadata, owners and relays (none in the
devnet; on the testnet up to 2 owners and 3 relays, of the single-host address and single-host name kinds),
every pool's delegators, an unregistered pool id asked for, the empty set, definite and indefinite maps and
committees, empty, keyless and keyed committees, and, on the derived states only, a retirement and future
parameters with and without a voting key. Not exercised: a retirement or future parameters the chain itself
reached, future parameters that differ from the pool's current ones, a pool with more than 23 delegators or
owners (sets above 23 members are written with an indefinite length, `encodeSet`, `Encoder.hs:479-491`), a
multi-host-name relay, and a
`GetLeiosCommittee` answer without a future committee (`queryLeiosCommittee` never gives one). The answers
are those of the states as decoded from the snapshots; the query's dispatch and the envelope around the
result, which consensus does, are cited, not run.

## Provenance and licence

Generated by this project from the [Leios build state files](../../../fixtures/leios/state/README.md) (a
throwaway local cluster of the published Leios testnet image, input-output-hk/ouroboros-leios, Apache-2.0,
not redistributed, and public chain data of the Leios testnet that the same image wrote), by the unchanged
cardano-ledger (Apache-2.0). The answers hold public keys, hashes and pool metadata already on that chain;
no key or secret is contained.
