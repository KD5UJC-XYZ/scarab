<!-- SPDX-License-Identifier: Apache-2.0 -->
# Block production

Part of [Architecture](../architecture.md).

## Block production (`follow --forge`)

```sh
follow ... --listen 127.0.0.1:3001 --forge --vrf-skey vrf.skey --kes-skey kes.skey --opcert opcert.cert
```

The node produces Praos (Conway) blocks for one pool. The files are the ones `cardano-cli` writes: the text
envelopes `VrfSigningKey_PraosVRF` (64 bytes, seed then public key), `KesSigningKey_ed25519_kes_2^6` (the
608-byte period-0 key) and `NodeOperationalCertificate` (`[[hotVkey, counter, kesPeriod, sigma], coldVkey]`,
which also gives the cold key, so the cold signing key is never needed). On start the three are
cross-checked: the certificate must name this KES key and be signed by its cold key.

**Cryptography** (`crypto`, pure, with vectors):

- `Draft03Prover` is ECVRF-ED25519-SHA512-Elligator2 draft-03 proving as libsodium does it. It reproduces the
  IETF draft-03 vectors 16 and 17 (public key, 80-byte proof and 64-byte output), and a property test checks
  that every proof verifies with `StrictDraft03` and certifies the same output. Secret scalars only meet the
  constant-time arithmetic of `curve25519-elisabeth`; the hash-to-curve step takes public inputs only.
- `Sum6KesSigning` is the cardano-base `SumKES` layout: a level-`d` key is `subKey || r1 || vk0 || vk1`, the
  signature the leaf signature followed by the `vk0 || vk1` of each level (448 bytes), the verification key
  `Blake2b-256(vk0 || vk1)`. `generate(seed)` expands seeds with `Blake2b-256(0x01 || seed)` and
  `Blake2b-256(0x02 || seed)`; `fixtures/kes-gen` has the seeds `cardano-cli node key-gen-KES` stored in its keys
  together with the verification key it computed, and regenerating the depth-5 tree from the seed gives exactly
  that verification key. `evolve` moves one period and zeroes what cannot be used again (the seed of the right
  half when it is generated, the whole left half when the right one takes over); `fromBytes` recomputes every
  level's stored verification keys and refuses a damaged key. A key from a file is at period 0 and is evolved to
  the slot's period (`slot / slotsPerKESPeriod - certificate start`) before it signs.

**Forging** (`node/forge`) runs from a per-epoch leader schedule ([#1101](https://github.com/KD5UJC-XYZ/scarab/issues/1101),
an approved Deviation; [design](../design/leader-schedule.md)). `Forger.run` runs four fibers in one scope, joined
at once, and a fatal outcome of any of them ends the others and is the run's result:

- **The scheduler** derives the inputs of the leader check of an epoch (its slots, the epoch nonce, the pool's
  stake and the distribution's total, the active-slot coefficient, the VRF key: `EpochInputs`) from the committed
  chain and the credentials in force, by the same derivation the forge uses: the epoch of the tip's ledger, and
  the next one once the tip is at or past its freeze slot (4k/f before its first slot), when its nonce cannot
  change on that chain any more. A new value restarts the computation (`LeaderSchedule`): each epoch's slots in
  chunks of 100, every slot's VRF output and a proof for a led slot only, paced to finish before the epoch starts,
  or, for an epoch already begun, a chunk ahead of the clock. The schedule is held in memory only.
- **The clock fiber** is the reference's blockchain time (`hardForkBlockchainTime`): it reads the wall clock over
  the era history (`EpochGeometry`) each slot and publishes the slot. Only a known slot is published, that is after
  the system start and while the era history at the tip translates the wall clock's slot (its horizon, the one
  the transactions' time translation has); otherwise the reading is traced (`currentSlotUnknown`) and the clock is
  asked again after 60 seconds (`BackoffDelay`). Each reading is checked against the previous one as the
  reference's `checkValidClockChange` checks it (consensus 82ecba329 `BlockchainTime/WallClock/HardFork.hs`
  121-151): the same or an earlier slot within 20 seconds of the previous reading's time (`hfbtMaxClockRewind`)
  is traced (`systemClockMovedBack`) and the previous slot kept; further back the node stops
  (`ClockProblem.SystemClockMovedBack`). The wait for the next slot is fixed at the reading and timed on the
  monotonic clock. It waits on no other fiber.
- **The slot driver** takes the slots as the reference's `knownSlotWatcher` hands them to its forging thread: a
  slot to its end, then the latest slot the clock has reached; a slot that passes meanwhile is passed over without
  a line or a count, unless the forging fiber has claimed it. It is the only publisher of a slot's outcome. Each
  slot has one claim, set once, and the driver's resolved-through mark is the floor below which nothing can be
  claimed. At each slot it first makes or reads the KES key's update of the slot's period (below). A slot the
  forging fiber owns gets that fiber's final result. Any other slot is decided with the checks the reference makes
  before its leader check, with no VRF and no ledger tick, in its order: the tip (`slotIsImmutable`,
  `blockFromFuture`), the forecast horizon (`noLedgerView`), the era of the ledger the view is forecast from
  (`forgeFailed`), the KES key's state (`forgeStateUpdateError`); then from the schedule, if it covers the slot
  under the inputs in force (derived from the block the slot would build on), or with `Leadership.check` at the
  slot. A slot not led logs only its ending line, after `startLeadershipCheck`, with `stateInfo` where the key's
  update succeeded, at a KES period change, and is counted as a slot checked; a led one is handed to the forging
  fiber. The `forgeEpoch` line of an epoch comes after every slot of it has been published.
- **The forging fiber** forges a led slot 50 ms before its onset, once every slot before it is settled, so that
  forges, and their signatures, run in slot order. The attempt is provisional until the onset: made again when
  the chain or the credentials change, and at the onset its block is handed to chain selection only if the block
  context, the slot's inputs and the credentials are still those it was made from; otherwise the slot is forged
  again from the node as it is then, as the per-slot loop forged it, and the discarded attempt logs one Debug line
  (`forgeDiscarded`). Every line of an attempt is held, with the time it was made, until the slot's outcome is
  final, and the slot driver publishes them in slot order. A slot the slot driver hands over is forged at once. A
  led slot of a KES period the clock has not reached at the wake, in practice a period's first slot, is not forged
  ahead, which saves a futile attempt: the slot driver hands it over at its onset, after its update of the period.

**The KES key at each period.** The slot driver updates the key at the first slot of each period it handles,
under a reservation that a forge holds from its own update to its signature, so that the key cannot move to the
next period between a forge's update and its signature; the lock order is the reservation, then the key's lock. Only
an attempt admitted at its slot changes the KES state for it (an evolution, a poisoning, a period's update to record):
one that began, or was made again, once the clock fiber had published the slot, as the slot driver's handling of a
slot and its handover are; it keeps the admission for its duration, as the per-slot loop goes on with a slot it has
begun should the clock go back. `HotKeys`' changing operations take the admission and check it under the key's lock,
which a reload takes too, so that a reload in a slot's wake margin finds the key the per-slot loop would have. An
attempt not admitted only reads the period's recorded update and signs with a key already at the slot's period; where
it would change the state it ends and the slot is made at its onset. A reload is the one exception: it brings a new
key to the wall clock's period, on both paths alike. One update is recorded per period and credentials
(a reload clears it), and every slot of the period reads it: the first failure is published as `KESCouldNotEvolve`
in the first slot, in slot order, whose checks reach the key, and `KESKeyAlreadyPoisoned` in each later one, as the
reference's poisoned key answers.

### The slots a pool leads

A led slot's attempt (`Forger.attempt`) follows `forkBlockForging` (consensus 82ecba329 `NodeKernel.hs` 549-796),
step by step and trace by trace, with the reference's trace names, severities and machine fields
([logging](../running/logging.md), the rows from `startLeadershipCheck` to `adoptedBlock`, and `stateInfo`):

1. `startLeadershipCheck`, the first act of the slot.
2. The block to build on (`mkCurrentBlockContext`, 831-891): the tip if it is in an earlier slot; when the tip
   holds the slot, an alternative to it on its predecessor (same height; chain selection then decides between
   the two), or the successor of a boundary block in the same slot. The slot is given up when the tip is in a
   later slot (`blockFromFuture`) or when no volatile block is left and the anchor is in the slot or a later one
   (`slotIsImmutable`). Otherwise `blockContext` and `ledgerState` (the state of every volatile block is held
   with it, so the reference's `NoLedgerState` race cannot happen).
3. The ledger view of the slot is forecast from the predecessor's ledger state: for the predecessor's own slot
   and every slot before the stability window after its successor (`ledgerViewForecastAt`,
   `Shelley/Ledger/SupportsProtocol.hs` 100-127; at genesis the bound is the window itself). Beyond it,
   `noLedgerView` (`Error`) and the slot is given up; within it, `ledgerView`. The tip's state is carried to the
   slot's epoch exactly as block validation does it (cached per tip and epoch); refusals of Scarab's own are
   made here (below).
4. The forge state is updated (`updateForgeState`, `HotKey.evolve`): the KES key is evolved to the slot's
   period when that is inside its certificate's window and later than its own, and written back before it signs
   (`stateInfo`). At or past the window's end the key is poisoned (erased from memory) and every slot gives
   `forgeStateUpdateError` (`Critical`) until a reload installs another key; before the window's start the key
   is left as it is.
5. The leader check is made again (decision 5 of the design): under inputs equal to the schedule's, the slot's
   output is evaluated again and the schedule's certificate is used; otherwise `Leadership.check` decides afresh.
   The check is on the stake alone (`checkIsLeader`): `Leadership` evaluates the VRF key on
   `Blake2b-256(slot || epochNonce)` and compares the output with the Praos threshold of the
   pool's relative stake in the forecast ledger view's distribution, the same check and the same
   distribution as for received headers: the predecessor's current one in its own epoch and two or more
   epochs on, the mark snapshot in the next epoch (TICKF, cardano-ledger f649f975 `Shelley/Rules/Tick.hs`
   201-242). Only a won slot is proved: the reference evaluates the certified VRF, output and proof, for
   every slot (consensus 808e4005 `Protocol/Praos.hs` 408-434), and the decision and a won slot's VRF output
   and proof are the same ([#1024](https://github.com/KD5UJC-XYZ/scarab/issues/1024#issuecomment-6902912834)).
   A VRF key that is not the registered one is found by the block's validation, as in the reference. Not
   elected: `nodeNotLeader`. Elected with a certificate whose window has not begun: `nodeCannotForge`
   (`PraosCannotForgeKeyNotUsableYet`). Otherwise `nodeIsLeader`.
6. The ledger is ticked to the slot (`forgeTickedLedgerState`), and the transactions are taken from the mempool
   (`BlockBuilder.select`, `forgingMempoolSnapshot`): the held transactions are revalidated in order against the
   ticked ledger in the block's own environment, those that no longer apply are dropped without counting, and
   of the rest the longest prefix whose measures fit the block's capacity in every component is taken
   (`snapshotTake`): the body size less 1024 bytes, each transaction counting its size plus 4; the execution
   units; the reference-script bytes of a block. The first transaction that does not fit ends the selection.
7. The body is encoded from the transactions' original bytes (bodies, witness sets, auxiliary data by index,
   invalid indices) and hashed as the header commits; the ten-element Praos header body (block number, slot,
   previous hash, cold key, VRF key, VRF result, body size and hash, operational certificate, the ledger's
   protocol version) is signed with the KES key at the slot's period (`forgedBlock`, then Scarab's `forged`).
8. At the onset, `Follower.adoptForged` runs the block through the node's own `MirrorRules` and commits it through the same
   path as any block (persistence, the chain server, mempool synchronisation): `adoptedBlock` (then `adopted`
   with the latency). A valid block that does not become the tip, because the tip has moved or chain selection
   prefers it, is `didntAdoptBlock` (then `lost`). A block our rules refuse is `forgedInvalidBlock`: its
   transactions leave the mempool, valid or not, and the slot ends.

The forger needs no peer: like the reference it forges on its own chain. Only Praos with Conway transactions
is forged: a TPraos era or Byron is `ForgeError.UnsupportedProtocol`, the ledger view of a
slot in an epoch before the tip's is `LedgerViewUnavailable`, a protocol version below 9 is refused after the
tick. Each of these is a `forgeFailed` (counted in `failed` and `scarab_forge_failed_total`). The reference
has none of these refusals (it forges in every Shelley-based era); the era refusals go when the node forges in
every era ([#544](https://github.com/KD5UJC-XYZ/scarab/issues/544)).

Two checks of the block just built only a bug of ours can fail: a body over the maximum size (the selection
bounds it) and a block that does not decode. Either is a `ForgeDefect`, logged as `forgeDefect` (Critical) and
counted in `defects` and `scarab_forge_defects_total`: the slot ends without a block, the block's transactions
leave the mempool, valid or not, so that the next leader slot does not build it again, and the node goes on, as
the reference goes on after a forged block its own rules refuse (`TraceForgedInvalidBlock`, `NodeKernel.hs`
757-787). The body is checked before the KES key evolves or signs. This is the owner's decision of 2026-11-14
([#987](https://github.com/KD5UJC-XYZ/scarab/issues/987), [decisions](../decisions.md)).

Errors are a closed ADT, `ForgeError` (`ledger/.../NodeError.scala`): the key files and their checks
(`KeyFile`, `KesKey`, `KesKeyNotCertified`, `CertificateFailure`, `CertificateSignatureRejected`,
`KesFileFailure`), the key's use (`KeyPeriodOutOfRange`, `KesEvolution`, `KesUpdateInterrupted`), the
leader check (`VrfInput`, `VrfProof`, `LeaderThreshold`, `NotLeader`, the ordinary answer), and the
refusals above (`LedgerViewUnavailable`, `UnsupportedProtocol`, `Assembly`).
Scarab's own log events beside the reference's traces: `forgeStarted`, `forged` (slot, block number, hash,
transaction ids, KES periods, stake), `adopted`, `lost` (the block did not join our chain, or was replaced at
its height later), `kesEvolved`, `kesReloaded`, `kesReloadRefused`, `kesPersistFailed` (see "Rotating the KES
key" in [running.md](../running.md)), `forgeFailed`, `forgeEpoch`. Whether the node forges is the reference's
`blockForgingUpdate`, at start-up and at each SIGHUP that finds the files or finds one absent: a reload that finds a credential file absent disables
forging, and no slot is started until a reload finds the files again (see "Dynamic block forging" in
[running/forging.md](../running/forging.md#dynamic-block-forging)).

The slot clock starts at the Byron genesis' `startTime` (whole seconds) when there is a Byron genesis
file, as the reference's hard-fork combinator does, and at the Shelley `systemStart` otherwise
(`Genesis.withSystemStart`; the `started` line logs the instant as `systemStart`). The two files agree on
the public networks, but a hand-made network can write them apart, and a node that used the Shelley value
then runs its slots a whole number of seconds away from the network's (roadmap H6).

Counters (`ForgeStats`, per epoch; roadmap H5) are folded over the published traces, so the log, `GET /forging`
and the metrics cannot disagree: slots checked (`slotsChecked`, the reference's `Forge.about-to-lead`, every slot
the slot driver handles, led or not), led
(`leaderSlots`), not led, could not forge, forge-state update errors, forged, adopted, slot battles lost at
adoption (`lostSlotBattles`), forged invalid, adopted blocks replaced later, Scarab's own refusals (`failed`),
forge defects (`defects`),
slots given up before the leader check by reason (`slotIsImmutable`, `blockFromFuture`, `noLedgerView`), and
the forge latency (slot start to adoption: count, mean, max). The reference's `ForgingStats` (`nodeIsLeader`,
`nodeCannotForge`, `blocksForged`, `slotsMissed`) are folded as cardano-node folds them and published at the
start of each slot ([metrics](../running/metrics.md)), folded in slot order. The schedule is published in the
gauges `scarab_forge_scheduled_leader_slots`, `scarab_forge_expected_leader_slots`, `scarab_forge_next_leader_slot`
and `scarab_forge_schedule_complete` and the `leaderSchedule` line: whoever reads them learns when the pool leads. A `forgeEpoch` line carries an epoch's counters when
the next epoch begins, and `GET /forging` on the query port serves the counters per epoch and in total (the
`pool` scenario reads it and asserts it against the log and the metrics: forged and adopted counts equal, no
block took more than five slots from its slot's start to adoption).

Tests: `ForgeSuite` forges with real keys against the real `MirrorRules` over a synthetic Conway ledger (blocks
are accepted, consecutive, counted for the pool, transactions leave the mempool, the KES key crosses period
boundaries, the order of the traces in a slot led and one not led, the forecast bound at genesis and after a
block, a certificate before its window and a key past it, a VRF key that is not the registered one, the
longest-prefix selection, lost and invalid adoption, body size accounting and a decode round trip),
`ForgeStatsSuite` and the `ForgingStats` cases the counters, `LeaderScheduleSuite` and `LeaderScheduleStreamSuite`
the schedule and its pacing, `ScheduledForgerSuite` the differential test of the scheduled forger against the
per-slot check and `ForgeInterleavingSuite` its fibers' interleavings (#1101), `KeyFilesSuite` loads a real `cardano-cli`
certificate, `Draft03ProverSuite` and `Sum6KesSigningSuite` hold the vectors.
