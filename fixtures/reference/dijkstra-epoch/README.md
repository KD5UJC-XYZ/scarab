<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra epoch boundary

Ledger states measured on 2026-11-18 against the unchanged Haskell ledger of the public Leios testnet build
(cardano-node `b004fe0c`, cardano-ledger `9df9f98c`: cardano-ledger-dijkstra 0.5.0.0, cardano-ledger-shelley
1.21.0.0), for [#54](https://github.com/KD5UJC-XYZ/scarab/issues/54). The oracle source and
[recipe](../../../scripts/oracle/dijkstra-epoch/README.md) are committed; `provenance.json` records the image's
immutable ID, the oracle build it accepted (the digests of the image's `Main.hs`, package file, cabal project and
executable, and the pinned checkouts found unchanged), the program's hash, the SHA-256 of each input, of each
ledger source the states depend on (as found in the image and checked against the pin) and of each state file.

## The cases

Each [Leios build state file](../../../fixtures/leios/state/README.md) used, its `NewEpochState` ticked with
Dijkstra's unchanged `TICK` along two paths (the [recipe](../../../scripts/oracle/dijkstra-epoch/README.md) says
why these slots), and six states derived from each (below). `cases.json` holds one case per input, derivation
and path, twenty-eight in all, in that order: each input's two paths (`derivation` `"none"`), then each
derivation's; and, under `genesis`, the [genesis case](#dijkstras-genesis-injection).

| input | path | slot: epoch, reward update after the tick (applied at the boundary?) | state bytes |
|---|---|---|---|
| `dijkstra-203`, the devnet's first Dijkstra state (epoch 1, tip 203; epochs of 200 slots, W = 100) | `boundaries` | 400: 2, absent (no); 600: 3, absent (no) | 20,186; 20,155 |
| | `rewards` | 301: 1, pulsing; 400: 2, absent (yes); 501: 2, pulsing; 600: 3, absent (yes) | 24,562; 20,262; 24,086; 20,327 |
| `testnet-2168639`, the public Leios testnet (epoch 100, tip 2,168,639; epochs of 21,600 slots, W = 8,640) | `boundaries` | 2,181,600: 101, absent (no); 2,203,200: 102, absent (no) | 505,632; 502,155 |
| | `rewards` | 2,168,641: 100, pulsing; 2,181,600: 101, absent (yes); 2,190,241: 101, pulsing; 2,203,200: 102, absent (yes) | 789,606; 505,624; 772,175; 502,119 |

A state's bytes are in `<input>-<path>-<slot>.cbor`, the `NewEpochState` as `toCBOR` writes it at version 12 (the
second field of the Shelley ledger state a snapshot's `state` file holds), for every state that crosses a boundary
and for the devnet's states inside an epoch; the testnet's two pulsing states (0.8 MB each) are recorded by digest
and size only, so a test that ticks through them compares its own encoding's digest.

### Derived states

Neither input has a retiring pool, future pool parameters or a VRF key hash two pools share, so the
[recipe](../../../scripts/oracle/dijkstra-epoch/README.md) derives four states from each
with the ledger's own lenses and functions to exercise Dijkstra's `POOLREAP`. P, Q and R are the input's
registered pools in key order, "new" the VRF key hash `ee…ee` (32 bytes), and the next epoch is 2 on the devnet
and 101 on the testnet. A case's `derivation` holds `pools` (each role's pool id) and `changes`, the
modifications in the order they are made, which is all a test needs to build the same state:

| `change` | fields | what it does |
|---|---|---|
| `setActiveVrf` | `pool`, `vrf` | the pool's `StakePoolState` takes `vrf` as its VRF key hash (`psStakePoolsL`, `spsVrfL`) |
| `setFutureParams` | `pool`, `network`, `vrf` | the pool's future parameters become `stakePoolStateToStakePoolParams network pool (its StakePoolState)` with VRF key hash `vrf` (`psFutureStakePoolParamsL`, `sppVrfL`) |
| `populateVRFKeyHashes` | | `psVRFKeyHashes` recomputed from the pools: one per active hash, one per future hash that differs from its pool's active one |
| `retire` | `pool`, `epoch` | the pool's retirement at `epoch` (`psRetiringL`) |
| `setMaxPledgeLeverage` | `numerator`, `denominator` | the current and the previous parameters' `ppMaxPledgeLeverageL` set to `MaxPledgeLeverage (SJust (numerator / denominator))` |
| `setFutureLeiosCommitteeSize` | `leiosCommitteeSize` | the governance state's future parameters set to `DefinitePParamsUpdate` of the current ones with `ppLeiosCommitteeSizeL` set (`cgsFuturePParamsL`) |

| derivation | changes | VRF key hash counts: derived state → after the boundary |
|---|---|---|
| `retire` | `retire` P | P's 1 → 0 |
| `reregister-new-vrf` | `setActiveVrf` Q to P's hash, `setFutureParams` P with "new", `populateVRFKeyHashes` | P's 2 → 1 (Q keeps it), "new" 1 → 1, Q's own 0 → 0 |
| `both` | as `reregister-new-vrf`, then `retire` R | P's 2 → 1, "new" 1 → 1, Q's own 0 → 0, R's 1 → 0 |
| `both-sharer` | as `reregister-new-vrf`, then `retire` Q | P's 2 → 0 (P moves off it, Q retires), "new" 1 → 1, Q's own 0 → 0 |
| `pledge-cap` | `setMaxPledgeLeverage` 1/1 | none (no pool or VRF role) |
| `committee-size` | `setFutureLeiosCommitteeSize` 2 | none |

Each derived state is ticked along both paths up to the first boundary (`boundaries`: the next epoch's first slot;
`rewards`: the reward start point, then that slot), with the same VRF key hash counts on both inputs. A derived
state's bytes are in `<input>-<derivation>.cbor` and its ticks' in `<input>-<derivation>-<path>-<slot>.cbor`, for the
devnet; the testnet's derived states (0.5 to 0.8 MB each) are recorded by digest and size only.

### Dijkstra's genesis injection

`genesis` in `cases.json` records the initial state of a chain that is in Dijkstra from its genesis, made from the
[Leios testnet's genesis files](../../../fixtures/leios/testnet/config/) as the node makes it: the era translations
from Byron's genesis UTxO (empty: the Byron genesis has no balance), then Dijkstra's `injectIntoTestState`; and, for
comparison, the same injection applied to `createInitialState` (the [recipe](../../../scripts/oracle/dijkstra-epoch/README.md)
cites both). Its fields: `name` (`testnet`), `genesis` (the four genesis files the program reads), `byronUtxo`
(`empty`), `version` (12), `translated` (the state the translations reach, before the injection, with its UTxO:
`stateSha256`, `bytes`, `file`), `fromByron` and `createInitialState` (each the injected state without its UTxO,
as the node's ledger snapshot holds it: `stateSha256`, `bytes`, `file`, `utxo` (the UTxO's CBOR, hex) and `facts`),
and `equal` (whether the two injected states and their UTxO are the same: they are not). The files are
`genesis-testnet-translated.cbor` (9,587 bytes), `genesis-testnet.cbor` (12,051) and
`genesis-testnet-create-initial-state.cbor` (7,689). With the ten base files and the devnet's 24 derived ones, the
fixture's 37 files take 2.8 MB.

| field | contents |
|---|---|
| `name`, `stateSha256`, `newEpochStateSpan`, `newEpochStateSha256` | the input, and where its `NewEpochState` lies in `state` (the span [the node-to-client query oracle](../leios-ntc-queries/README.md) reads) |
| `tipSlot`, `epoch`, `genesis` | the ledger state's tip, the `NewEpochState`'s epoch, which the tip's slot is in, and the Shelley genesis file the globals are made of |
| `version`, `roundTrip` | 12, and the input encodes back to its own bytes |
| `globals` | what the oracle's `Globals` give: `epochSize`, `securityParameter`, `stabilityWindow` (`3k/f`), `randomnessStabilisationWindow` (`4k/f`), `maxKESEvo`, `slotsPerKESPeriod`, the Leios key age `maxKeyAge` Dijkstra's SNAP derives from them (40,178 epochs on the devnet, 374 on the testnet), and the first slots of the input's epoch and of the next two |
| `inputFacts`, `ticks[].facts` | read from each state with the ledger's accessors: `epoch`, `rewardUpdate` (`absent`, `pulsing` or `complete`, the `nesRu` field), `treasury`, `reserves`, `pools` and `poolsRetiring` (the pool state's maps), `fees` (the snapshots' fee), and the mark (`epoch`, `committeeSize`, `maxKeyAge`, `seats`, `keyedSeats`) and set (`seats`, `keyedSeats`) snapshots' Leios committees |
| `inputFacts`, `ticks[].facts` (continued) | `futurePools` (pools with future parameters), `vrfKeyHashes` (distinct hashes in `psVRFKeyHashes`), `deposited` (the UTxO state's deposits), `accountBalances` (the reward accounts' balances, summed), `leiosCommitteeSize` and `maxPledgeLeverage` (the current parameters'; the cap as `[numerator, denominator]` or null), `futurePParams` (`none`, `definite` or `potential`) |
| `derivation` | `"none"`, or the derived state: `name`, `pools` (role → pool id), `changes` (above), `stateSha256`, `bytes`, `file` (or null), `facts`, and `vrfCounts` (role → `{vrf, count}`, the count in `psVRFKeyHashes`, 0 when absent; roles `P`, `new`, `Q`, `R` as the derivation names them) |
| `ticks[]` | each tick in order: `slot`, `stateSha256`, `bytes`, `file` (or null), `facts`, `crossedBoundary`, `rewardUpdateApplied` (a boundary crossed by a state holding a reward update), `poolsRetired` (pools registered before the tick and not after) and `vrfCounts` (as the derivation's, `{}` without one) |

## What the states show

- Every boundary makes a mark snapshot of the new epoch with a Leios committee of `ppLeiosCommitteeSize` (900)
  and the key age the Shelley genesis's KES setup gives, `ceiling(62 × 129,600 / L) + 2`: 40,178 for the devnet's
  200-slot epochs and 374 for the testnet's 21,600-slot ones, the key age the node itself wrote into each input's
  mark. The old mark's committee moves to the set position. The devnet's committee has three seats and no keys
  (no pool there has a voting key), the testnet's 154 seats, all keyed (every pool has one), fewer than the
  committee size.
- A boundary applies a reward update only if the state entering it holds one. Both inputs hold none (their tips
  lie before their epoch's reward start point, slot EL + W), and a tick at an epoch's first slot is too early for
  `RUPD` to start one, so on `boundaries` neither boundary applies a reward update: reserves and treasury stay
  as they were, and the testnet's fee pot is not drained (the snapshots' fee is 374,107,671,341 after both
  boundaries). On `rewards` the tick past the start point leaves a pulser (`pulsing`), which `NEWEPOCH`
  completes and applies: on the devnet the reserves fall from 199,991,000,000,000 to 199,866,805,589,002 and then
  199,863,623,094,485, on the testnet from 14,231,792,686,148,440 to 14,224,294,265,516,808 and then
  14,221,533,794,266,336, the treasury rising with them.
- No pool retires on the inputs themselves: neither has a retirement scheduled. On the derived states the retiring
  pool leaves the pool state and the next mark (the devnet's mark has 2 seats after `retire`, the testnet's 153),
  while the set keeps the old mark's committee; its 500 ADA deposit leaves `deposited` and goes to its reward
  account when that is registered (the testnet's P) or to the treasury when not (every devnet pool, the testnet's
  Q). A re-registered pool's future parameters become active and `futurePools` returns to 0.
- `POOLREAP` releases one reference to a VRF key hash for each pool that stops using it, by retiring or by moving to
  other future parameters, and drops the hash only when no reference is left: P's hash, shared with Q, counts 1 after
  P moves to "new", and 0 when Q also retires.
- A pledge leverage cap of 1 binds on both inputs: the reward update the pulser computes with it pays the reward
  accounts less at the boundary than the uncapped one, the rest staying in the reserves, the treasury's share the
  same. The devnet's pools pledge nothing, so their reward is 0 under any cap: reward accounts 0 against
  7,199,675,998, reserves 199,874,005,265,000 against 199,866,805,589,002. The testnet's pools pledge some, so the cap
  cuts their reward without ending it: 14,706,870,559,916 against 14,844,238,231,823 in the reward accounts,
  14,224,431,633,188,715 against 14,224,294,265,516,808 in the reserves.
- Definite future parameters with committee size 2 are enacted at the boundary, and the new mark is made with them:
  committee size 2 and 2 seats (the devnet's keyless, the testnet's keyed), while the set keeps the old mark's 3 or
  154. The parameters then hold 2, and the future parameters go back to `potential`, as after every boundary.
- The genesis case's injected states hold the genesis's pool, its VRF key hash counted once, and a committee of one
  seat in both the mark and the set, keyless although the genesis gives the pool a voting key; committee size 900
  and key age 374, as the Dijkstra and Shelley genesis files give them. The state the node makes differs from the
  injection applied to `createInitialState` only in the governance state: the translation into Conway installs the
  Conway genesis's constitutional committee and constitution, and the DRep pulser built from them, which
  `createInitialState` leaves empty. Their facts and UTxO are the same.
- Ticking a state straight to the first slot of the epoch after next crosses no boundary: `NEWEPOCH` acts only
  when the slot's epoch is the state's epoch plus one
  (`eras/conway/impl/src/Cardano/Ledger/Conway/Rules/NewEpoch.hs:160`).
  (Checked once on the devnet input: the state comes back byte for byte.) Both paths therefore tick each
  boundary from the epoch before it.

## Scope

Exercised: one and two consecutive boundaries in Dijkstra, with and without a reward update (from a pulser started,
never forced), a pulsing reward update in the encoding, a Leios committee with keyless and with keyed seats rotating
from mark to set, and the key age derived from two networks' KES setups and epoch sizes. On the derived states only:
a pool retirement with and without a registered reward account, future parameters with another VRF key hash, a
VRF key hash two pools share, a binding pledge leverage cap, and a committee size changed at the boundary, with more
pools than seats. Also Dijkstra's genesis injection on one network's genesis files. Not exercised: a governance
action enacted or expired, a protocol-version change (`HARDFORK`), a reward update forced complete late in an epoch
(`RewardsTooLate`), a genesis with Byron balances or more than one pool, a retirement, re-registration or parameter
change the chain itself reached (no ledger rule makes the derived states, and
Dijkstra's `POOL` refuses a VRF key hash another pool holds), and future parameters that change more than the VRF key
hash. The states are those of the ledger's `NewEpochState` only; consensus's ledger state around it (tip, transition,
cumulative transaction bytes) and the header state are not ticked here.

## Provenance and licence

Generated by this project from the [Leios build state files](../../../fixtures/leios/state/README.md) (a
throwaway local cluster of the published Leios testnet image, input-output-hk/ouroboros-leios, Apache-2.0, not
redistributed, and public chain data of the Leios testnet that the same image wrote), by the unchanged
cardano-ledger (Apache-2.0). The states hold public keys, hashes and pool metadata already on those chains; no key
or secret is contained.
