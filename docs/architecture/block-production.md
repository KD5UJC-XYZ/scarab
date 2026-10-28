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

**Forging** (`node/forge`). `Forger` runs one attempt per slot from the wall clock over the era history
(`EpochGeometry`), as the reference's `knownSlotWatcher` hands slots to `forkBlockForging`: only a known slot
runs, that is after the system start and while the era history at the tip of the chain translates the wall
clock's slot (its horizon, the one the transactions' time translation has); otherwise nothing runs, nothing is
traced or counted, and the clock is asked again after 60 seconds (`CurrentSlotUnknown`, `BackoffDelay`). A
passed slot is never forged late, and a slot that passes while an earlier attempt is still running is passed
over without a trace. The slot never goes back.

### The forging loop

Each slot follows `forkBlockForging` (consensus 82ecba329 `NodeKernel.hs` 549-796), step by step and trace by
trace, with the reference's trace names, severities and machine fields ([logging](../running/logging.md),
the rows from `startLeadershipCheck` to `adoptedBlock`, and `stateInfo`):

1. `startLeadershipCheck`, the first act of every slot.
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
5. The leader check is on the stake alone (`checkIsLeader`): `Leadership` proves
   `Blake2b-256(slot || epochNonce)` with the VRF key and compares the output with the Praos threshold of the
   pool's relative stake in the forecast ledger view's distribution, the same check and the same
   distribution as for received headers: the predecessor's current one in its own epoch and two or more
   epochs on, the mark snapshot in the next epoch (TICKF, cardano-ledger f649f975 `Shelley/Rules/Tick.hs`
   201-242). A VRF key that
   is not the registered one is found by the block's validation, as in the reference. Not elected:
   `nodeNotLeader`. Elected with a certificate whose window has not begun: `nodeCannotForge`
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
8. `Follower.adoptForged` runs the block through the node's own `MirrorRules` and commits it through the same
   path as any block (persistence, the chain server, mempool synchronisation): `adoptedBlock` (then `adopted`
   with the latency). A valid block that does not become the tip, because the tip has moved or chain selection
   prefers it, is `didntAdoptBlock` (then `lost`). A block our rules refuse is `forgedInvalidBlock`: its
   transactions leave the mempool, valid or not, and the slot ends.

The forger needs no peer: like the reference it forges on its own chain. Only Praos with Conway transactions
is forged: a TPraos era, Byron or the bootstrap mode is `ForgeError.UnsupportedProtocol`, the ledger view of a
slot in an epoch before the tip's is `LedgerViewUnavailable`, a protocol version below 9 is refused after the
tick, and a body over the maximum size or a block that does not decode is `Assembly`. Each of these is a
`forgeFailed` (counted in `failed` and `scarab_forge_failed_total`). The reference has none of these refusals
(it forges in every Shelley-based era and bounds the body through the transaction selection); they are kept
pending the owner's decision under [#399](https://github.com/KD5UJC-XYZ/scarab/issues/399).

Errors are a closed ADT, `ForgeError` (`ledger/.../NodeError.scala`): the key files and their checks
(`KeyFile`, `KesKey`, `KesKeyNotCertified`, `CertificateFailure`, `CertificateSignatureRejected`,
`KesFileFailure`), the key's use (`KeyPeriodOutOfRange`, `KesEvolution`, `KesUpdateInterrupted`), the
leader check (`VrfInput`, `VrfProof`, `LeaderThreshold`, `NotLeader`, the ordinary answer), and the
refusals above (`LedgerViewUnavailable`, `UnsupportedProtocol`, `Assembly`).
Scarab's own log events beside the reference's traces: `forgeStarted`, `forged` (slot, block number, hash,
transaction ids, KES periods, stake), `adopted`, `lost` (the block did not join our chain, or was replaced at
its height later), `kesEvolved`, `kesReloaded`, `kesReloadRefused`, `kesPersistFailed` (see "Rotating the KES
key" in [running.md](../running.md)), `forgeFailed`, `forgeEpoch`.

The slot clock starts at the Byron genesis' `startTime` (whole seconds) when there is a Byron genesis
file, as the reference's hard-fork combinator does, and at the Shelley `systemStart` otherwise
(`Genesis.withSystemStart`; the `started` line logs the instant as `systemStart`). The two files agree on
the public networks, but a hand-made network can write them apart, and a node that used the Shelley value
then runs its slots a whole number of seconds away from the network's (roadmap H6). Unlike the reference, a
clock set back by more than 20 seconds is not fatal (`SystemClockMovedBack`): the loop waits for the slot it
had reached.

Counters (`ForgeStats`, per epoch; roadmap H5) are folded over the loop's traces, so the log, `GET /forging`
and the metrics cannot disagree: slots started (`slotsChecked`, the reference's `Forge.about-to-lead`), led
(`leaderSlots`), not led, could not forge, forge-state update errors, forged, adopted, slot battles lost at
adoption (`lostSlotBattles`), forged invalid, adopted blocks replaced later, Scarab's own refusals (`failed`),
slots given up before the leader check by reason (`slotIsImmutable`, `blockFromFuture`, `noLedgerView`), and
the forge latency (slot start to adoption: count, mean, max). The reference's `ForgingStats` (`nodeIsLeader`,
`nodeCannotForge`, `blocksForged`, `slotsMissed`) are folded as cardano-node folds them and published at the
start of each slot ([metrics](../running/metrics.md)). A `forgeEpoch` line carries an epoch's counters when
the next epoch begins, and `GET /forging` on the query port serves the counters per epoch and in total (the
`pool` scenario reads it and asserts it against the log and the metrics: forged and adopted counts equal, no
block took more than five slots from its slot's start to adoption).

Tests: `ForgeSuite` forges with real keys against the real `MirrorRules` over a synthetic Conway ledger (blocks
are accepted, consecutive, counted for the pool, transactions leave the mempool, the KES key crosses period
boundaries, the order of the traces in a slot led and one not led, the forecast bound at genesis and after a
block, a certificate before its window and a key past it, a VRF key that is not the registered one, the
longest-prefix selection, lost and invalid adoption, body size accounting and a decode round trip),
`ForgeStatsSuite` and the `ForgingStats` cases the counters, `KeyFilesSuite` loads a real `cardano-cli`
certificate, `Draft03ProverSuite` and `Sum6KesSigningSuite` hold the vectors.
