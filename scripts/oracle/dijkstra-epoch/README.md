<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra epoch boundary

**Status: Current** — optional offline reference oracle for [#54](https://github.com/KD5UJC-XYZ/scarab/issues/54).

Build the [Leios testnet oracle image](../leios-testnet/README.md) first, then:

```sh
docker build --label xyz.kd5ujc.scarab.kind=oracle \
  -f scripts/oracle/dijkstra-epoch/Dockerfile -t scarab-dijkstra-epoch-oracle:local .
python3 scripts/oracle/dijkstra-epoch/run.py            # offline: inputs, program and fixture match the provenance
python3 scripts/oracle/dijkstra-epoch/run.py --replay   # run the image (no network, read-only) and compare
python3 scripts/oracle/dijkstra-epoch/run.py --record   # rewrite the fixture and its provenance
python3 scripts/oracle/dijkstra-epoch/run.py --record --accept-build   # ... for a build reviewed anew
```

The inputs are two of the [Leios build state files](../../../fixtures/leios/state/README.md): `dijkstra-203`,
the first Dijkstra state of a local devnet (genesis files in `fixtures/leios/state/genesis/`), and
`testnet-2168639`, the public Leios testnet at epoch 100 (genesis files in `fixtures/leios/testnet/config/`).
`run.py` takes the ledger's `NewEpochState` out of each as the [node-to-client query
oracle](../leios-ntc-queries/README.md) does (whose reader it imports): the current era of the telescope, and in
it `encodeShelleyLedgerState`'s `[2, [tip, NewEpochState, transition, cumulative transaction bytes]]`
(ouroboros-consensus `2416493b` `Shelley/Ledger/Ledger.hs:885-903`). It refuses an input whose tip's slot is not in
the `NewEpochState`'s epoch with every epoch of the Shelley genesis's length, or lies past the epoch's reward start
point. `Main.hs` then runs only unchanged cardano-ledger `9df9f98c` code, the revision cardano-node `b004fe0c` pins:

- it reads the state with `fromCBOR` (Dijkstra's `fromEraCBOR`, version 12), as consensus does
  (`Shelley/Ledger/Ledger.hs:918`), and refuses to go on unless `toCBOR` gives the state's bytes back;
- it makes the globals as consensus does (`mkShelleyLedgerConfig`, `Shelley/Ledger/Ledger.hs:226-239`):
  `mkShelleyGlobals` of the network's Shelley genesis file, with the epoch information of a chain whose every
  era has the genesis's epoch size, which is what the hard-fork combinator answers for both networks (both
  configurations start Shelley at epoch 0, so Byron has no epoch, and every Shelley-based era, Dijkstra at epoch 1
  on the devnet and at 0 on the testnet, takes the Shelley genesis's epoch length). `maxKESEvo` and
  `slotsPerKESPeriod` come from the genesis file, and Dijkstra's SNAP turns them into the Leios committee's key
  age (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Snap.hs:105-179`);
- it ticks the state with Dijkstra's `TICK` (`applyTickNoEvents`,
  `eras/shelley/impl/src/Cardano/Ledger/Shelley/API/Validation.hs:186-193`), as consensus ticks a ledger state
  (`applyChainTickLedgerResult`, `Shelley/Ledger/Ledger.hs:586-624`), along two paths, each tick from the state
  the one before reached, and writes every state reached with `toCBOR`.

The paths, from a state of epoch E with epochs of L slots and a randomness stabilisation window W (`4k/f`
rounded up, `eras/shelley/impl/src/Cardano/Ledger/Shelley/Genesis.hs:768-789`):

| path | slots | what it exercises |
|---|---|---|
| `boundaries` | (E+1)L, (E+2)L | two boundaries in a row, with no tick between them: each enters its epoch with no reward update, as both inputs hold none |
| `rewards` | EL+W+1, (E+1)L, (E+1)L+W+1, (E+2)L | the same two boundaries, each after a tick at the first slot past its epoch's reward start point, where `RUPD` starts the reward pulser (`eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Rupd.hs:107-152`), so `NEWEPOCH` completes and applies it (`eras/conway/impl/src/Cardano/Ledger/Conway/Rules/NewEpoch.hs:153-184`) |

Neither input has a pool retirement, future pool parameters or a VRF key hash two pools share, so Dijkstra's
`POOLREAP` and its VRF key hash counting (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/PoolReap.hs:104-212`)
would go unexercised. `Main.hs` therefore also derives states from each input before ticking it, four for these, with the
ledger's own lenses and functions and nothing patched, as the [node-to-client query
oracle](../leios-ntc-queries/README.md) derives its states. P, Q and R are the input's registered pools in key order;
"new" is the VRF key hash of 32 bytes of `0xee`, registered to no pool (`Main.hs` refuses an input where it is):

| derivation | changes, in order |
|---|---|
| `retire` | P retires at the next epoch (`psRetiringL`) |
| `reregister-new-vrf` | Q's active VRF key hash becomes P's (`psStakePoolsL`, `spsVrfL`); P's future parameters are its own (`stakePoolStateToStakePoolParams` with the Shelley genesis's network, `Testnet` for both inputs) with VRF key hash "new" (`psFutureStakePoolParamsL`, `sppVrfL`); the counts are recomputed by `populateVRFKeyHashes`, so P's hash counts 2, "new" 1 and Q's own hash 0 |
| `both` | `reregister-new-vrf`, then R retires at the next epoch |
| `both-sharer` | `reregister-new-vrf`, then Q, the pool sharing P's hash, retires at the next epoch |

No ledger rule makes these states: Dijkstra's `POOL` refuses a registration whose VRF key hash another pool holds
(`Rules/Pool.hs:252-254`), so the shared hash is not one a Dijkstra registration makes. `populateVRFKeyHashes`,
which the translation into Dijkstra runs (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Translation.hs:116`),
counts such a hash, and `POOLREAP` releases one reference per pool that stops using it, where Shelley's drops the
hash (`PoolReap.hs:48-54, 108-160`). Each derived state is ticked along both paths up to the first boundary: the first
slot of the next epoch, and the reward start point then that slot. `Main.hs` reports, for each state, the counts of
the derivation's VRF key hashes by role (P's, "new", Q's own and R's), and `run.py` checks them against the counts
`populateVRFKeyHashes` gives for the pools P, Q and R (the invariant `POOLREAP` keeps, `Rules/Pool.hs:176-186`), on
the derived state and after the boundary, where the future parameters are active and the retiring pools are gone.

Two more derivations change the protocol parameters instead, through the ledger's own lenses:

| derivation | changes | what it exercises |
|---|---|---|
| `pledge-cap` | the current and previous parameters' `ppMaxPledgeLeverageL` set to `MaxPledgeLeverage (SJust 1)` | the CIP-50 cap in `maxPool'` (`libs/cardano-ledger-core/src/Cardano/Ledger/State/SnapShots.hs:141-163`), which the reward pulser reads from the previous parameters (`eras/shelley/impl/src/Cardano/Ledger/Shelley/LedgerState/PulsingReward.hs:120`, `Rewards.hs:361, 385`): a pool's share is capped at the leverage times its pledge |
| `committee-size` | the governance state's future parameters set to `DefinitePParamsUpdate` of the current ones with `ppLeiosCommitteeSizeL` 2 (`cgsFuturePParamsL`) | a committee size that changes at the boundary: Dijkstra's `EPOCH` enacts the future parameters (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Epoch.hs:190`) before `SNAP` reads them (`:237`), so the new mark carries the new size |

`run.py` checks, on the `rewards` path, that the capped reward update is applied and pays the reward accounts less
than the uncapped one at the same slot (the base case's), the rest staying in the reserves and the treasury's share
the same; and, at every boundary, that the new mark's committee size is the parameter in force after it, with no
more seats than that size.

### Dijkstra's genesis injection

`Main.hs genesis` makes the initial state of a chain that is in Dijkstra from its genesis, on the [Leios testnet's
genesis files](../../../fixtures/leios/testnet/config/) (every era at epoch 0; its Shelley genesis injects one pool
with a voting key, one delegation and two funds). It follows the node: consensus translates Byron's state through
every era and then applies the current era's `injectIntoTestState` (ouroboros-consensus `2416493b`
`Ouroboros/Consensus/Cardano/Node.hs:846-892`, the translations in `Cardano/CanHardFork.hs:351-379, 550-737`), so
`Main.hs` runs `translateToShelleyLedgerStateFromUtxo` on Byron's genesis UTxO, then `translateEra'` into Allegra,
Mary, Alonzo, Babbage, Conway and Dijkstra with each era's translation context, then Dijkstra's
`injectIntoTestState` (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Transition.hs:59-101`: Conway's injection,
`populateVRFKeyHashes`, `seatInitialLeiosCommittee`), with the transition configuration composed as
cardano-ledger-api's `mkLatestTransitionConfig` composes it. Byron's genesis UTxO is empty because the Byron
genesis has no AVVM or non-AVVM balance, which `run.py` checks. For comparison it also applies the injection to
`createInitialState` of the same configuration, as the [Leios genesis harness](../leios-genesis/README.md) does
for Conway. The two differ: the translation into Conway installs the Conway genesis's constitutional committee and
constitution, and the DRep pulser is built from them, where `createInitialState` starts from an empty governance
state. This case lives here rather than in the Leios genesis harness, because that harness compiles a program per
run against Conway and compares native results case by case, while this one is a measured image whose record
already binds Dijkstra's sources.

The states the ticks reach need nothing but the input state. The snapshot's `NewEpochState` holds no UTxO (it is
in the snapshot's `tables`), and consensus ticks it without them (`Shelley/Ledger/Ledger.hs:607-609`); Dijkstra's
`EPOCH`, `SNAP` and `POOLREAP` read the instant stake, the deposits, fees and donations of the UTxO state, never
the UTxO itself. Only an event reads it: `NEWEPOCH`'s `TotalAdaPotsEvent` sums the UTxO's coin
(`eras/shelley/impl/src/Cardano/Ledger/Shelley/AdaPots.hs:51-66`), which `applyTickNoEvents` discards unread.

`run.py` checks each result against what the rules fix, so a wrong input or global is refused rather than
recorded: the globals are those the genesis file gives (epoch size, both stability windows, the KES setup and the
key age `ceiling(maxKESEvo × slotsPerKESPeriod / L) + 2`), and that key age is the one the node wrote into the
input's mark snapshot; each state's epoch is the one its slot is in; each boundary makes a mark of that epoch with
the input's committee size and key age, moves the old mark's committee into the set position and leaves no reward
update; a reward update is applied exactly when the state ticked from held one; and on `rewards` a pulser is
running after each tick inside an epoch.

Consensus is not compiled in the image (see the [Leios testnet harness](../leios-testnet/README.md)), so the parts
of the tick that are consensus's (the ledger state's transition counter, the header state) are cited above, not
run. `Main.hs` is a second package of the Leios testnet harness's cabal project (`scarab-dijkstra-epoch.cabal`),
built by cabal against that project's dependencies. The build is not `--offline`, as the node-to-client query
oracle's is not; the measurement below shows cabal, offline, finding the executable up to date.

`run.py` does not trust the image's tag. It resolves the image once to its immutable ID, which the measurement, the
runs and the provenance all use, and measures that image as the node-to-client query oracle measures its own: the
image's `Main.hs`, package file and cabal project are the repository's; each `source-repository-package` checkout is
at the commit the cabal project pins (cardano-ledger `9df9f98c` among them) and unchanged; cabal, offline, finds the
executable up to date; the entry point is that executable; and the ledger sources the states depend on (TICK, RUPD,
NEWEPOCH, Dijkstra's EPOCH, SNAP, POOL and POOLREAP, the genesis, the snapshots, the pool state, the certificate
state and the `NewEpochState` encoding, each by its full path) have their pinned digests. The measurement runs as the
image's own user, with nothing mounted, since cabal's store and configuration are that user's; the oracle itself runs
as an unprivileged user, read-only, with its inputs mounted read-only. The provenance binds the image's immutable ID
and the executable's and the build files' digests, and `--replay` and `--record` refuse an image with another ID or
whose build differs from them; `--record --accept-build` accepts an image reviewed anew. The
[fixture](../../../fixtures/reference/dijkstra-epoch/README.md) records the states, the pins and the scope. No
public-network connection, node or key is needed.
