<!-- SPDX-License-Identifier: Apache-2.0 -->
# Forging from a per-epoch leader schedule

**Status**: Current. Accepted 2026-11-16: the owner's decisions on
[#1101](https://github.com/KD5UJC-XYZ/scarab/issues/1101), and its reference claims checked independently at their pins
(the pull request's review). Built in stages S1 to S3 (§8); §11 says what was built and what its evidence shows. **No
persisted format changes**: the schedule is held in memory only (§5.2, [FMT-1](../design-rules.md#fmt-1)).

Pins. The forger cites the `11.1` build: cardano-node 11.1.3, ouroboros-consensus 82ecba32, cardano-ledger f649f975,
and for the files of its cardano-ledger-shelley package cardano-ledger 4c81e909 (the revision that package is built
from; the Shelley files cited here are identical at f649f975). The re-pin to cardano-node 11.2.1
([#1002](https://github.com/KD5UJC-XYZ/scarab/issues/1002), whose stage 0 landed with #1017) moves the area to
ouroboros-consensus 808e4005 and cardano-ledger 29a7d656. Every claim about the reference was read at both builds;
§2.8 says where they differ, and [`docs/data/reference-pins.json`](../data/reference-pins.json) has every pin. Paths
are abbreviated: consensus files from `Ouroboros/Consensus/` in their package (`-diffusion`, `-protocol`, `-cardano`
with its `shelley` sublibrary, and at 808e4005 the `tracing` sublibrary); ledger files from `Cardano/Ledger/` in their
package, and `TPraos/Rules/Updn.hs` from `Cardano/Protocol/` in cardano-protocol-tpraos; cardano-node files from
`cardano-node/src/Cardano/Node/`. Scarab code is named by its symbols, not its lines.

## 1. What the forger does now

`Forger`, in the node's `forge` package, runs the reference's forging thread slot by slot
([block production](../architecture/block-production.md#the-slots-a-pool-leads)). Its `loop` reads the wall clock over the
era history and checks each reading against the previous one as the reference's `checkValidClockChange` does: a
slot that moved back within 20 s (`Forger.MaxClockRewind`) is traced as `systemClockMovedBack` and kept, and further
back the node stops. It hosts the #1032 assertion that a wake 1 µs to 1 ms before the slot is no clock moved back
(`Forger.wokeEarly`, `ClockWokeEarly`), and it waits for the next slot on the monotonic clock (`untilNext`). For every
new slot while forging is enabled, `stepInScope` runs `attempt`:

- it traces `startLeadershipCheck`;
- it picks the block to build on (`blockContext`), checks the forecast range (`forecast`), and carries the tip's state
  into the slot's epoch (`crossedTo`, cached per tip and epoch): the pool distribution from
  `MirrorEraRead.leaderPools` and the epoch nonce from `Praos.tick`;
- it updates the KES key to the slot's period (`HotKeys.update`: evolve and write back, or poison past the
  certificate's window) and traces `stateInfo`;
- it runs the leader check, `Leadership.check`: since #1098 the VRF output alone (`Draft03Prover`'s `output`), and a
  proof only for a won slot;
- for a led slot it ticks the ledger, selects from the mempool, builds and signs the block, and adopts it through
  `Follower.adoptForged`.

Each step is traced as the reference traces it (`ForgeTrace`). `ForgeStats` folds the traces into the per-epoch
counters (`ForgeEpochStats`, the `forgeEpoch` line, `GET /forging`) and into the reference's `ForgingStats`, whose
gauges it publishes at each `startLeadershipCheck`.

So every slot the pool does not lead still runs all of that up to the leader check, and logs `startLeadershipCheck`,
`stateInfo` and `nodeNotLeader` at Info, and `blockContext`, `ledgerState` and `ledgerView` at Debug. In a profile of
a forging Scarab pool on 0.1 s slots the VRF was 15% of its CPU and the loop's other work about 3% (#1101). #1098
builds a proof only for a won slot; the VRF output of every slot and the rest of the loop stay on every slot's path,
at the slot.

## 2. What the reference does

All of §2 is derived from source, not from a capture. §2.9 lists each claim with its pins.

### 2.1 The forging thread, once per slot

`forkBlockForging` runs the forging action under `knownSlotWatcher` of the blockchain time, which calls it whenever
the known slot changes (C1). The watcher runs the action to its end, then waits for a slot other than the last one it
handled and takes the latest: a slot that passes while an action runs is skipped, with no trace ("some updates may be
missed", in the `Watcher`'s own comment). At 82ecba32 the action is the local `go` of `NodeKernel.hs`; at 808e4005 it
is `forge` of `NodeKernel/Forge.hs`, with the same steps and traces (C2):

1. `TraceStartLeadershipCheck`.
2. The block context from the current chain (`mkCurrentBlockContext`): the tip if it is in an earlier slot; an
   alternative to a tip in this slot; `TraceSlotIsImmutable` or `TraceBlockFromFuture` and the slot ends. Then
   `TraceBlockContext`.
3. The predecessor's ledger state (`TraceNoLedgerState` and the end, or `TraceLedgerState`), and its ledger view
   forecast to the slot (`TraceNoLedgerView` and the end, or `TraceLedgerView`). The forecast answers only for slots
   before the stability window after the predecessor's successor (C16).
4. `checkShouldForge` (C3): first `updateForgeState`. If it fails, `TraceForgeStateUpdateError`. If it is suppressed
   (`ForgeStateUpdateSuppressed`), the slot is not led, `TraceNodeNotLeader`, with no forge state traced; what
   produces that answer was not read. If it succeeds, the forge state is traced (`StateInfo`), then `checkIsLeader`
   decides: not leader is `TraceNodeNotLeader`; leader but `checkCanForge` refuses is `TraceNodeCannotForge`;
   otherwise `TraceNodeIsLeader`.
5. For a leader: the ledger ticked to the slot, the mempool snapshot, the block forged (`TraceForgedBlock`) and added
   to the chain database, then `TraceDidntAdoptBlock`, `TraceForgedInvalidBlock` or `TraceAdoptedBlock`.

`checkIsLeader` for Praos evaluates the certified VRF, `VRF.evalCertified`, on `mkInputVRF slot eta0` with the
ticked epoch nonce, and compares its leader value with the threshold of the pool's relative stake in the ledger view's
pool distribution (`meetsLeaderThreshold`) (C4). `checkCanForge` for Praos is `praosCheckCanForge`: the certificate's
first KES period must not be after the slot's (`PraosCannotForgeKeyNotUsableYet`) (C5). `updateForgeState` is
`HotKey.evolve` to the slot's KES period, `slot div slotsPerKESPeriod` (C6). It runs only in a slot that reached step 4:
a slot given up at step 2 or 3 evolves nothing.

### 2.2 Its traces

Every constructor of the trace type carries the slot being forged, and `TraceBlockFromFuture` also the later slot of
the chain's tip; no constructor publishes a leadership schedule (C10). The severities are the same at both builds
(C7): `StartLeadershipCheck`, `NodeNotLeader`, `NodeIsLeader`, `ForgedBlock` and `AdoptedBlock` Info; `BlockContext`,
`LedgerState`, `LedgerView`, `ForgeTickedLedgerState` and `ForgingMempoolSnapshot` Debug; `SlotIsImmutable`,
`BlockFromFuture`, `NoLedgerState`, `NoLedgerView`, `NodeCannotForge`, `DidntAdoptBlock` and `ForgedInvalidBlock`
Error; `ForgeStateUpdateError` Critical. `StateInfo` is Info, Warning with seven KES periods left or fewer, and Alert
with one or none. So a slot not led that reaches the leader check, after a forge state update that succeeded with more
than seven KES periods left, gives three Info lines (`StartLeadershipCheck`, `StateInfo`, `NodeNotLeader`) and three
Debug lines.

The metrics follow the traces, but not one to one (C8). `Forge.about-to-lead` counts `StartLeadershipCheck`,
`Forge.node-not-leader` counts `NodeNotLeader` and `Forge.node-is-leader` counts `NodeIsLeader`; `SlotIsImmutable`,
`BlockFromFuture`, `DidntAdoptBlock`, `ForgedInvalidBlock`, `AdoptedBlock` and `AdoptionThreadDied` have a counter
each; `NoLedgerState`, `NoLedgerView` and `NodeCannotForge` share `Forge.could-not-forge`; `ForgedBlock` adds to
`Forge.forged` and sets the `forgedSlotLast` gauge; `ForgeStateUpdateError` counts nothing and writes gauges:
`Forge.StateUpdateError`, the slot, and, when the error carries the key's state, the certificate's start and expiry
periods and zero for the current and remaining periods; the five Debug steps write no metric. The `ForgingStats`
gauges (`nodeIsLeader`, `nodeCannotForge`, `blocksForged`, `slotsMissed`) are folded over the traces and published
only when `StartLeadershipCheck` is traced. `NodeIsLeader` and `NodeNotLeader` both set the last slot seen, and
`slotsMissed` adds the gap between the slot of a `NodeNotLeader` and that last slot (C9).

Scarab mirrors every one of these traces with its severity, and their fields but for two renderings (C21): its
`forgedInvalidBlock` `reason` is Scarab's rendering of its own rules' refusal, not the reference's
`ExtValidationError`, and its `adoptedBlock` never carries the `txIds` the reference adds at its `DDetailed` level
([logging](../running/logging.md), the rows from `startLeadershipCheck` to `adoptedBlock`, and `stateInfo`).
`NoLedgerState` and `AdoptionThreadDied` have no case in Scarab, which cannot reach them (`ForgeTrace`).

### 2.3 The epoch nonce: frozen, then fixed at the boundary

Each header's VRF output folds into the evolving nonce; the candidate nonce takes the new evolving nonce only for a
header whose `slot + window < firstSlotNextEpoch`, and keeps its value otherwise; `labNonce` becomes the nonce of the
header's previous-hash field (C11). The window is the randomness stabilisation window: 4k/f slots
(`computeRandomnessStabilisationWindow`, the ceiling of 4k/f) in every Praos era except Babbage, which keeps 3k/f
(`computeStabilityWindow`) for compatibility; the TPraos eras freeze their candidate in UPDN with the ledger's
`stabilityWindow`, also 3k/f (C12). Scarab forges only in Conway, where the window is 4k/f.

At the first tick into a new epoch, the epoch nonce becomes `candidate ⭒ lastEpochBlockNonce`, the old epoch nonce
becomes the previous epoch nonce, and `lastEpochBlockNonce` becomes the `labNonce` of that moment (C13). So the nonce of
epoch e+1 combines two values held in epoch e: `lastEpochBlockNonce`, set at the start of epoch e and fixed for all of
it, and the candidate, which stops changing at the freeze slot `F = firstSlot(e+1) - 4k/f`.

What follows is derived, not a line of the reference:

- **Determined.** Once a chain holds a block at or after `F`, every block that can extend it is at a later slot and
  leaves the candidate unchanged, so on that chain the next epoch's nonce is fixed. Before that, a late block in a slot
  before `F` can still extend the chain and change it, with no rollback: passing `F` on the clock is not enough.
- **Settled.** After that, only a fork that rolls back past every block at or after `F` can change it: one that
  replaces a block before `F`, or one that removes the blocks at or after `F` and appends a block in a slot before `F`.
  So the nonce is settled once a block at or after `F` is immutable. Scarab's `VolatileChain` keeps the last k blocks
  and cannot roll back past its anchor, so the first block at or after `F` becomes immutable when k blocks follow it:
  once k + 1 blocks at or after `F` are on the chain. The k-block bound mirrors the reference's chain database; that
  bound was not read again for this document.

### 2.4 The stake distribution of the next epoch

At the boundary into epoch e+1, NEWEPOCH sets `nesPd` to `ssStakeMarkPoolDistr` of the snapshots as they were before
that boundary's SNAP (C15). SNAP had set that field at the previous boundary, into epoch e, to the pool distribution of
the mark snapshot it took then: the stake at the end of epoch e-1. The ledger view's forecast into the next epoch
(TICKF) takes the same field. So the pool distribution that elects epoch e+1's leaders is fixed on a chain from the
first tick into epoch e, well before the nonce; it changes only if a fork replaces blocks of epoch e-1.

### 2.5 Headers from the future

A ChainSync client records each header's arrival time and judges it against its slot's onset (C17). A header that
arrives early by no more than the clock skew is held until its onset (a `threadDelay`, "the simple part of Ouroboros
Chronos"); one that arrives earlier still raises `FarFutureHeaderException`, and the client disconnects from the peer
(`InFutureHeaderExceedsClockSkew`). The skew is `defaultClockSkew`, 2 s, which `stdLowLevelRunNodeArgsIO` passes and
cardano-node's `Run.hs` does not override. The forging code's own comment says that the chain database should not
contain blocks from the future; what the chain database does with one added locally was not read (unverified).

The source therefore does not require a block to be released at its onset: a peer holds a header up to 2 s early and
refuses it beyond that. Releasing a block at its slot's onset, not before, is this design's choice (§5.3): Scarab
never publishes a header early on purpose, and the skew allowance stays whole for the disagreement of clocks. It
cannot stop a peer whose clock runs behind Scarab's from receiving the header before that peer's own onset and holding
it; that is the case the skew allows for, as the source's own example of two nodes half a second apart describes
(C17).

### 2.6 The KES key

`HotKey.evolve` (`evolveKey`) leaves a key before its certificate's window as it is; poisons a key at or past the end
of the window (its memory erased, every later update `KESKeyAlreadyPoisoned`); and inside the window evolves it one
period at a time, each step `updateKES` followed by `forgetSignKeyKES` of the old key, "Clear the memory associated
with the old key" (C6). That erasure gives the live key forward security: once the key in memory has moved to period
t, it cannot sign for a period before t. It covers the key in memory only. With a key file, cardano-node reads the
signing key from that file (`readLeaderCredentialsSingleton`, `opCertKesKeyCheck`) and its reader does not write the
file back, so the file can still hold the key of an earlier period (C20). Scarab writes the evolved key back over its
file ([ADR 0015](../adr/0015-kes-key-written-back-and-checked-reload.md)). Since the update runs in each slot that
reaches the leader check, the key moves at the first such slot of a new period, which on a synced node is the
period's first slot; while the node gives slots up before the leader check (no ledger view, for example) the key does
not move.

### 2.7 The clock

The blockchain time runs in a linked thread of its own (`hardForkBlockchainTime`), apart from the forging thread
(C18). It sleeps until the next slot, reads the clock, and checks the reading against the previous one
(`checkValidClockChange`): a later slot is taken; the same or an earlier slot within `hfbtMaxClockRewind` is traced
(`TraceSystemClockMovedBack`) and the previous slot kept; further back it throws `SystemClockMovedBack`. A slot it
cannot translate is traced (`TraceCurrentSlotUnknown`) and asked for again after the backoff. The thread writes each
slot to a variable that the forging thread's watcher reads, and waits on no reader. The node passes a rewind limit of
20 s and a backoff of 60 s. Scarab's forger reads the clock inside its loop; the same checks move to the clock fiber
of §5.4, which has the reference's shape: a thread for the clock, apart from forging, that writes the slot for its
readers and waits on none of them.

### 2.8 Where the 11.1 and 11.2 builds differ

On the leader check, the nonce, the stake, the KES key, the clock and headers from the future, the two builds agree.
They differ in four places, none of which changes a decision, a certificate or a trace's name, order or severity:

- **Where the loop lives.** At 82ecba32 `forkBlockForging` holds the whole loop (`NodeKernel.hs` 549-800). At 808e4005
  `NodeKernel.hs` 533-561 only starts the watcher, and the steps and traces are `forge` and its helpers in
  `NodeKernel/Forge.hs`. One step moved: at 808e4005 `TraceForgingMempoolSnapshot` reads the mempool's tip after the
  transactions are selected, at 82ecba32 before.
- **Where the traces are rendered.** In 11.1.3 cardano-node renders them (`Tracing/Tracers/Consensus.hs`,
  `Tracing/Tracers/KESInfo.hs`); in 11.2.1 the consensus `tracing` sublibrary does (`Tracing/Consensus.hs`,
  `Tracing/KESInfo.hs`). The severities and counters are the same.
- **`slotsMissed`.** 11.2.1's `ForgingStats` does not count the slots that passed while forging was disabled (a resume
  generation, `Tracing/Tracers/ForgingStats.hs` 24-79 and 146-197); 11.1.3's counts them.
- **The mark snapshot.** At 29a7d656 SNAP stores the mark snapshot with its epoch and a Leios committee size (zero
  before Dijkstra); `ssStakeMarkPoolDistr` is computed as at f649f975.

Which build governs follows [REF-3](../design-rules.md#ref-3): the selected build, so `slotsMissed` follows 11.1.3
until the forging area moves with #1002.

### 2.9 The claims

| # | Claim | `11.1` build | `11.2` build | Checked |
|---|---|---|---|---|
| C1 | The forging thread runs its action under `knownSlotWatcher`, on each change of the known slot; the watcher retries while the slot is unknown. `runWatcher` runs the action, then blocks until the slot differs from the last one handled and takes the latest, so slots that pass during an action are skipped. | 82ecba32 `NodeKernel.hs` 549-572; `BlockchainTime/API.hs` 57-77; `Util/STM.hs` 47-56, 104-152 | 808e4005 `NodeKernel.hs` 533-561; `BlockchainTime/API.hs` 57-77; `Util/STM.hs` the same lines (identical) | yes |
| C2 | The steps and traces of a slot, in the order of §2.1. | 82ecba32 `NodeKernel.hs` 573-800, `mkCurrentBlockContext` 831-891 | 808e4005 `NodeKernel/Forge.hs` `forge` 61-151, helpers 166-495 | yes |
| C3 | `checkShouldForge`: `updateForgeState`, then the forge state traced, then `checkIsLeader`, then `checkCanForge`; a suppressed update is `NotLeader`. | 82ecba32 `Block/Forging.hs` 180-226 | 808e4005 `Block/Forging.hs` 163-209 | yes; what produces a suppressed update was not read |
| C4 | `checkIsLeader`: `VRF.evalCertified` of `mkInputVRF slot eta0` against `meetsLeaderThreshold` of the ledger view's pool distribution. | 82ecba32 `Protocol/Praos.hs` 402-424, 535-555 | 808e4005 `Protocol/Praos.hs` 408-430, 541-561 | yes |
| C5 | `checkCanForge` is `praosCheckCanForge`: the certificate's first KES period not after the slot's. | 82ecba32 `Shelley/Node/Praos.hs` 86-89; `Protocol/Praos.hs` 691-711 | 808e4005 the same file 86-89; `Protocol/Praos.hs` 697-717 | yes |
| C6 | `updateForgeState` is `HotKey.evolve` to `slot div slotsPerKESPeriod`; `evolveKey` keeps, poisons, or evolves with `forgetSignKeyKES` of each old key. | 82ecba32 `Shelley/Node/Praos.hs` 56-58, 83-85; `Protocol/Ledger/HotKey.hs` 359-370, 385-440 | 808e4005 the same lines (`HotKey.hs` identical) | yes |
| C7 | The severities of §2.2. | 11.1.3 `Tracing/Tracers/Consensus.hs` 1580-1599; `Tracing/Tracers/KESInfo.hs` 129-150 | 808e4005 `Tracing/Consensus.hs` 1739-1758; `Tracing/KESInfo.hs` 219-241 | yes |
| C8 | The metrics of the traces as §2.2 maps them: a counter for some, `Forge.could-not-forge` shared by three refusals, gauges for `ForgeStateUpdateError` and `ForgedBlock`, none for the Debug steps. | 11.1.3 `Tracing/Tracers/Consensus.hs` 1487-1538 | 808e4005 `Tracing/Consensus.hs` 1646-1697 | yes |
| C9 | `ForgingStats` is folded over the traces and published at `StartLeadershipCheck`; `NodeIsLeader` and `NodeNotLeader` set the last slot; `slotsMissed` adds the gap at a `NodeNotLeader`. | 11.1.3 `Tracing/Tracers/ForgingStats.hs` 83-121 (`NodeIsLeader` 102-105) | 11.2.1 the same file 146-197 (`NodeIsLeader` 167-174), with the resume generation (24-79) | yes; differs (§2.8) |
| C10 | Every forging trace carries the slot being forged (`TraceBlockFromFuture` also the tip's later slot); no constructor publishes a leadership schedule. | 82ecba32 `Node/Tracers.hs` 272-404 (`TraceBlockFromFuture` 295-304) | 808e4005 `Node/Tracers.hs` 291-423 (314-323) | yes, for the forging traces; other trace types were not surveyed |
| C11 | Per header: evolving nonce extended; candidate set only if `slot + window < firstSlotNextEpoch`; `labNonce` from the previous hash. | 82ecba32 `Protocol/Praos.hs` 500-533 | 808e4005 `Protocol/Praos.hs` 506-539 | yes |
| C12 | Window: 4k/f for Praos but Babbage, 3k/f for Babbage; TPraos UPDN uses the ledger's `stabilityWindow`, 3k/f. | 82ecba32 `Cardano/Node.hs` 683-697, 788-799; 4c81e909 `Shelley/StabilityWindow.hs` 14-35, `Shelley/Genesis.hs` 786-789; f649f975 `TPraos/Rules/Updn.hs` 60-75 | 808e4005 `Cardano/Node.hs` 627-641, 732-743; 29a7d656 the same ledger lines | yes |
| C13 | At a new epoch: epoch nonce `candidate ⭒ lastEpochBlockNonce`; previous epoch nonce the old one; `lastEpochBlockNonce` the `labNonce`. | 82ecba32 `Protocol/Praos.hs` 436-463 | 808e4005 `Protocol/Praos.hs` 442-469 | yes |
| C14 | The nonce is determined once a block at or after the freeze slot is on the chain, and settled once a block at or after it is immutable: k + 1 blocks at or after it. | derived from C11, C13 and the k-block rollback bound | the same | derived; the rollback bound not re-read |
| C15 | NEWEPOCH sets `nesPd` to `ssStakeMarkPoolDistr` before SNAP; SNAP sets that field to the new mark's pool distribution; TICKF into the next epoch takes the same field. | f649f975 `Conway/Rules/NewEpoch.hs` 154-188, `Conway/Rules/Tickf.hs` 42-66; 4c81e909 `Shelley/Rules/Snap.hs` 94-102 | 29a7d656 the same Conway lines; `Snap.hs` 94-103 | yes |
| C16 | The ledger view is forecast only before the stability window after the state's slot's successor. | 82ecba32 `Shelley/Ledger/SupportsProtocol.hs` 100-127 | 808e4005 the same lines | yes |
| C17 | A header up to the skew early is held until its onset; earlier, the client disconnects; the skew, 2 s, allows for clocks that disagree; cardano-node sets no other. | 82ecba32 `MiniProtocol/ChainSync/Client/InFutureCheck.hs` 134-176, 182-207; `MiniProtocol/ChainSync/Client.hs` 1751-1775; `Node.hs` 589, 1107-1108; 11.1.3 `Run.hs` | 808e4005 the same `InFutureCheck.hs` lines; `Client.hs` 1757-1781; `Node.hs` 592, 1140-1141; 11.2.1 `Run.hs` | yes; "no override" by search of `Run.hs`. The chain database's handling of a future block: unverified |
| C18 | The blockchain time is its own thread, which writes the slot to a variable its readers read (`slotVar`, `getCurrentSlot`); `checkValidClockChange`; rewind limit 20 s, backoff 60 s. | 82ecba32 `BlockchainTime/WallClock/HardFork.hs` 68-178; `Node.hs` 547-561 | 808e4005 the same `HardFork.hs` lines; `Node.hs` 550-564 | yes |
| C19 | `setBlockForging` hands a list of credentials to `blockForgingController`, which cancels the forging threads and forks one per entry: with none, no forging thread runs and no slot is traced. | 82ecba32 `NodeKernel.hs` 416, 428-439 | 808e4005 `NodeKernel.hs` 399, 411-422 | yes |
| C20 | With a key file (`KESKeyFilePath`), the node reads the KES signing key from it, checked against the certificate (`readLeaderCredentialsSingleton`, `opCertKesKeyCheck`); the reader writes nothing back. | 11.1.3 `Protocol/Shelley.hs` 191-195, 220-231 | 11.2.1 `Protocol/Shelley.hs` 187-191, 216-227 | yes for the reader; that nothing else in the node rewrites the file is from [ADR 0015](../adr/0015-kes-key-written-back-and-checked-reload.md), not re-read |
| C21 | The machine rendering of `TraceForgedInvalidBlock` carries the reference's `ExtValidationError`; that of `TraceAdoptedBlock` adds `txIds` at `DDetailed` only. | 11.1.3 `Tracing/Tracers/Consensus.hs` 1374-1399 | 808e4005 `Tracing/Consensus.hs` 1519-1546 | yes |

## 3. Prior art: Amaru

pragma-org/amaru forges from a schedule computed once per epoch. This is its engineering decision record 035
(`engineering-decision-records/035-block-forging.md`, status "proposed") at revision
33d62a6f1ccde08d77fbc202a2cfb888d0b3f7c6:

- every input of the leader check is fixed before the epoch starts (lines 14-20);
- on the first new tip inside the 4k/f window the stage computes the next epoch's nonce and asks, off the stage, for
  the epoch's led slots (89-95);
- only the next led slot is armed, 50 ms before its onset; the block is sent to chain selection at onset, not before,
  "so we do not publish a future header" (95-97);
- a rollback that reaches back before the window can change the candidate and recomputes the schedule, and at
  start-up the current epoch is computed from the tip's nonce (99-103).

Its `crates/amaru-consensus/src/stages/forge_block/schedule.rs` keys a pending computation by its nonce and drops a
result computed from a replaced one (15-19, 94-114). Its `crates/amaru-ouroboros/src/praos/leader.rs` builds the full
proof for every slot of the epoch (`lead_slot`, 33-49). Scarab differs from Amaru in three ways: it evaluates the
output alone and proves only a led slot (#1098); it spreads the work over the time left; and it checks a led slot again
at the slot.

## 4. The decisions

The questions were asked on 2026-11-16 at 00:54 UTC. The owner decided at 01:03 and 01:04 UTC, and approved the
design at 01:09 UTC, to be built once the independent review has checked its claims about the reference
([#1101](https://github.com/KD5UJC-XYZ/scarab/issues/1101)). They bind this design:

0. Build it now.
1. Full forging detail only for led slots. The clock is still watched every slot. For every slot not led the
   `nodeNotLeader` line is kept, because operators' tools may read it; it costs about 0.3% of CPU at 0.1 s slots,
   measured from the log path's share of samples.
2. A per-slot clock watcher keeps the reference's handling of a clock that moved back: the trace, and the stop beyond
   the rewind limit.
3. A timer at each KES period boundary evolves the key and updates the KES gauges, as promptly as the per-slot loop.
4. The schedule is computed as soon as the next epoch's nonce is known, spread over the time left and finished before
   the epoch starts; an fs2 stream paced by that deadline, restarted by a new nonce, stake distribution or VRF key.
5. At a led slot, the slot's leadership is checked again against the chain as it is then, before forging.
6. The block is forged ahead of its slot, so that it can be released as soon as possible, as Amaru does.
7. The schedule is published to the operator: a Prometheus metric of the epoch's potential leader slots, and a log
   line.
8. The per-slot loop is replaced outright, with no flag for the old one.
9. Every slot's leadership decision and every won slot's certificate are byte-identical to the per-slot loop's; the
   evidence is a differential test of both paths over generated epochs, the devnet `pool` scenario and one Antithesis
   run.

## 5. The design

### 5.1 Four fibers in one scope

`Forger.run` keeps its signature and its owner: `Main` starts it in the background and lands its result, as now
([FIB-2](../design-rules.md#fib-2)). Inside its scope it runs four fibers and joins them at once (`parSequence_` or
`race`), so they may use its capabilities ([FIB-3](../design-rules.md#fib-3)), and a `Fatal` from any of them ends all
four and is returned as now:

- the **scheduler** (§5.2), which computes the schedule;
- the **clock fiber** (§5.4), which reads the clock each slot and publishes the slot;
- the **forging fiber** (§5.3), which wakes a margin before each led slot and forges it, but for a slot of a KES
  period the clock has not reached, which it forges at the onset, and publishes nothing;
- the **slot driver** (§5.4), which takes each slot from the clock fiber and is the only publisher of a slot's
  outcome: its lines, the forge counters and gauges, and the KES update of each period (§5.5).

Decision 2's per-slot clock watcher is the clock fiber together with the slot driver's per-slot work, and decision 3's
timer is the slot driver's update at the first slot of each KES period.

They share three pieces of state: the schedule (§5.2); one claim per slot and the slot driver's resolved-through mark,
which say who owns a slot's outcome and how far the outcomes are settled (§5.4); and the key reservation with the
result of each KES period's update (§5.5). The clock fiber waits on no one. The forging fiber waits only for the slot
driver's mark, before a forge starts and holding nothing; the slot driver waits on the forging fiber's result for the
slot just past the mark, which the forging fiber can always forge, and on the key reservation, never on both at once.
So no cycle of waits can form (§5.4).

Time comes only from the `now` and `sleep` the forger already takes, so every test runs under `TestControl`
([EFF-6](../design-rules.md#eff-6), [TST-2](../design-rules.md#tst-2)). The per-slot loop (`loop`, `stepInScope`) is
removed; `attempt` remains as the forging of one led slot, from `startLeadershipCheck` onward (decision 8).

### 5.2 The schedule

**Inputs.** An epoch's leader check depends on: the epoch (its slot range), the epoch nonce, the pool's stake and the
total of the distribution that elects it, the active slot coefficient, and the VRF key. These are one value, compared
by value (the VRF key by its public key). They are derived by the same code as `crossedTo` derives them for a slot: the
nonce from `Praos.tick` of the tip's protocol state, the stake from `MirrorEraRead.leaderPools`. The two paths cannot
disagree on the inputs, so the schedule's decisions equal the per-slot decisions by construction, and the differential
test (§7) checks it. An epoch's inputs are known from a tip in that epoch, and the next epoch's from a tip at or past
its freeze slot (§2.3).

**The signal.** A signal holds the inputs of the epochs the pool can still lead in: the epoch the clock is in and,
once known, the next. It is derived from the committed chain, through the follower's committed window (a
`SignallingRef`, today private to `Follower`), and from the credentials in force (`HotKeys`; none while forging is
disabled). It is deduplicated (`changes`), so it moves only when an input moves: a fork that replaces a block before
the freeze, a fork across the previous boundary that changes the mark snapshot, a SIGHUP that installs another VRF
key, forging switched off or on. An epoch whose slots have all passed is never computed (Amaru's discussion point,
"schedule while syncing").

**The stream.** One long-lived fiber runs `signal.discrete.switchMap(computeAll)`: a new value cancels the computation
under way and starts again from the new inputs. For each epoch of the value it walks the slots in order, in chunks of
a fixed number of slots (proposed: 100). For each chunk it evaluates the output-only check of every slot
(`Leadership.decide`, the `output` and the threshold) and, for a led slot only, builds its proof; it then adds the
chunk's led slots and certificates to the schedule and sleeps for

`(time left until the epoch's first slot - margin - chunks left × the last chunk's computation time) / (chunks left)`

but never so long that the next chunk, taking as long as the last, would end less than that time again before the
onset of the first slot the schedule has not covered. So the work is spread and done before the epoch starts when each
chunk takes no longer than the one before it and the chunks left fit in the time left less the margin; chunks that
grow (1 s, 1.9 s, 3.7 s for a 300-slot epoch of 0.1 s slots, signalled 12 s before it) can end after the epoch's first
slot, while the schedule still stays ahead of the clock. The proposed margin is a tenth of the time from the signal to
the epoch's first slot (4.8 hours on mainnet, 4 seconds on the trial devnet): room for a pause or a slow host, without
crowding the end.

**Start-up and a late signal.** When the epoch has already begun (the node starts mid-epoch, forging is switched on, a
VRF key is reloaded), the stream computes the epoch's remaining slots in order from the clock's slot, with no pause
until it is one chunk ahead of the clock, and then paces the rest to finish by the onset of the epoch's last chunk
(one chunk, 100 slots, before its end), with a margin of a tenth of the time from the signal to that deadline. Paced
so, it stays ahead once a chunk ahead: each chunk's slots begin after the chunk is computed. Pacing to the epoch's end
itself would not: the last chunks would be computed after their slots begin (on the trial devnet, a signal at slot 750
of a 500-slot epoch ending at 1000 leaves a margin of about 25 slots, less than a chunk). A slot the clock reaches
before the stream covers it gets the per-slot decision from the slot driver (§5.4, Q3 in §9). The schedule of such an
epoch covers it from the clock's slot where its computation started, not from its first slot, and a computation that
falls behind the clock resumes at the clock's slot and drops the range it had (`EpochSchedule.next` and `added`): the
schedule then covers the epoch from there. Complete, it reaches the epoch's last slot from where its range starts; its
led slots, in the gauge and the `leaderSchedule` line, are that range's, and the expected count is the whole epoch's
(§5.6).

**What the pacing assumes of the host.** Once a chunk ahead, the stream stays ahead of the clock, each chunk computed
before its first slot begins, as long as computing a chunk takes less than the time its slots take to pass, less one
slot (99 s on mainnet, 9.9 s on the trial devnet, against about 0.1 s for 100 evaluations at the estimate of §6), and
less than twice as long as the chunk before it: the time the last chunk took is the margin the pause keeps before
the first slot not covered. A chunk that takes longer can end after the clock has reached its first slots; the
stream then goes on from the clock's slot, and each slot the clock reaches before the stream covers it gets the
per-slot decision from the slot driver (§5.4, Q3 in §9). An epoch whose chunks do not fit in the time left is computed
without pauses and finished late, its first slots decided the same way.

**It degrades, it does not fail.** The schedule's guarantees come in three tiers, and each slot is decided under the
best one the host allows:

1. **The target:** the whole epoch's schedule before the epoch starts. This is what the operator's metrics (§5.6) rely
   on to show the epoch's potential leader slots ahead of it, and it leaves a day or more of slack on mainnet.
2. **Late, but ahead:** chunks that finish after the epoch has started are not a fault. The requirement that matters
   is that each chunk is computed before its own first slot, and the pause's cap keeps that while the host meets the
   assumption above.
3. **Per slot:** a slot the clock reaches before the schedule covers it is decided at the slot by the slot driver,
   with `Leadership.check` and the inputs in force, as the per-slot loop decides every slot today.

So a slow or paused host costs only how early the schedule is known, never a slot's decision (decision 9).

**State.** A `Ref` (or `SignallingRef`, so that the forging fiber wakes on a change) holding, per epoch, the inputs the
schedule was computed from, how far it has got, and the led slots with their certificates. An answer for a slot is
given only by a schedule whose inputs equal the inputs in force for that slot: a schedule computed from replaced inputs
cannot answer. Epochs whose slots have all passed are dropped. The state is in memory only: persisting it would be a
persisted format, frozen by [FMT-1](../design-rules.md#fmt-1). A restart computes it again.

### 5.3 Forging a led slot

The forging fiber takes the earliest led slot after the last it handled, and sleeps until the slot's onset less the
wake margin and until the slot driver has settled every slot before it (the mark of §5.4, forging follows slot order).
A change of the schedule interrupts the sleep, and it takes the earliest again; so does a slot the slot driver claims
for it (§5.4 step 3), which it forges at once. The proposed margin is **50 ms**:

- Amaru uses it;
- twenty devnet `pool` runs with Scarab on one CPU measured a median of 4 to 10 ms from a slot's start to its
  signed block (`forged`) ([evidence log 3](../compatibility/evidence-log-3.md)), so 50 ms holds that work with room
  to spare;
- it is half of the devnet's 0.1 s slot, so the forging of a slot still starts after the previous slot's onset;
- it is far inside the reference's 2 s skew.

**A slot of a KES period the clock has not reached is not forged ahead.** If the slot's KES period is later than the
clock's at the wake, in practice when the slot is the first of a period, the fiber passes it over. An early forge's
update of the key (§5.5) would move the key into the period before the clock does, and a SIGHUP in the margin, whose
certificate is checked against the clock's period, would then be refused where the per-slot loop takes it; at the
certificate's expiry the key would be erased early ([#1101](https://github.com/KD5UJC-XYZ/scarab/issues/1101), the
review of [#1109](https://github.com/KD5UJC-XYZ/scarab/pull/1109)). The slot driver, at the onset and after its update
of the period, finds the slot led and claims it for the forging fiber (§5.4 step 3), which forges it at once, as the
per-slot loop forges it: such a slot's block is built after its onset, a Mirror (§5.7). The same holds in every attempt
made before the onset, the first and any made again: just before its update of the key it checks the clock, and where
the slot's period is later than the clock's (a pending slot whose attempt is made again after a reload, the wall clock
moved back within the rewind allowance into the period before), it ends there, refused with no line of its own, gives
the reservation back, and the slot is made at its onset, as after an early refusal (below).

At the wake the fiber makes an **early attempt**. It is provisional until the slot's onset:

1. It claims the slot (§5.4); a slot the slot driver already owns is left to it. A slot whose time has passed and that
   no one has claimed is passed over, as the reference passes over a slot its thread did not reach; a slot the slot
   driver has claimed for it is forged at once, even late in the slot, as the reference forges a slot its watcher has
   handed to it.
2. The full traced sequence runs as now, from `startLeadershipCheck` (decision 1): the block context and the forecast
   check, the forge state (`HotKeys.update`, `stateInfo`), the leader check and `checkCanForge`.
3. The leader check is made again against the chain as it is then (decision 5). The inputs are derived as in §5.2. If
   they equal the schedule's, the slot's output is evaluated once more and the schedule's certificate is used. If they
   differ, `Leadership.check` decides afresh and proves a won slot, exactly as the per-slot loop would.
4. The tick, the mempool snapshot, the block built and signed, as now. The attempt records what it was made from: its
   block context (the parent and the block number), the slot's inputs (the nonce, the pool's stake and the total, the
   VRF key), and the credentials in force (the KES key and the certificate, from the same reload). **Every line of the
   early attempt, from `startLeadershipCheck` to `forgedBlock`, and the forge counters are held** until the block is
   handed over or the slot's refusal is final, so the log shows one attempt per slot, the one that counted ([#1101,
   Q4](https://github.com/KD5UJC-XYZ/scarab/issues/1101#issuecomment-6905164395): lines cover only what is final). The
   forging fiber publishes none of them: it hands the slot's final result to the slot driver, which publishes it
   (§5.4). A held line keeps the time it was made: its `at` is the event's time, not the publication's, so a block
   forged before the onset shows its `forged` time before the onset (Q11), and the lines of a slot can be published
   after lines of later times.

**An early refusal is retried up to the onset.** If the early attempt gives the slot up (`noLedgerView`,
`slotIsImmutable` or `blockFromFuture` because the tip is not yet where it will be, or not led because the inputs it
derived differ from the schedule's), the slot stays pending: the attempt is made again whenever the chain or the
credentials change before the onset, and once more at the onset. Its refusal line is held, and the refusal that stands
at the onset is the slot's final result, which the slot driver publishes once, as the reference logs it in the slot. A
final result of not led (the inputs changed, and the slot is not led under them) is published as any slot not led is
(§5.4 step 4): its pre-check line or `nodeNotLeader`, and the counters; the attempt's held lines are dropped, with one
Debug line. So the outcome of such a slot does not depend on which fiber reached it first.

**At the onset the pending block is checked against the node as it is then.** It is handed to chain selection
(`Follower.adoptForged`), and its held lines and counters go to the slot driver as the slot's result, only if all
three hold:

- the block context at the onset (`blockContext` on the chain as it is then) is the one the block was built on;
- the slot's inputs derived at the onset are those the attempt was made from;
- forging is still enabled with the same credentials.

If any of them differs, the pending block is discarded and the slot is forged again at the onset from the node as it
is then, exactly as the per-slot loop forges in the slot; that block is handed over as soon as it is built. The
discarded attempt's held lines are dropped and it logs one Debug line instead (Q4), so the slot's sequence appears
once, the re-forge's. A change of credentials before the onset (a SIGHUP that installs another VRF key, KES key or certificate, or
that disables forging) discards a pending block at once, so nothing signed with replaced credentials is handed over.
Where the reference cancels its forging thread on such a reload (`blockForgingController`, C19), the attempt here runs
to that check and is discarded there; nothing an operator sees differs but the one Debug line.

The forging fiber handles one slot at a time and does not cancel a forge under way when the schedule changes, as
Amaru's stage does not; the HotKeys lock already orders signing against a reload. So the block handed over for a slot
is the one the per-slot loop would forge at the onset from the same parent, inputs and credentials; only the mempool
snapshot can be up to the margin older, so a transaction that arrives in those milliseconds waits for the next block
(part of decision 6).

### 5.4 The clock fiber and the slot driver

**The clock fiber** is the blockchain-time part of the forger's `loop`, and has the reference's shape: a thread of its
own that reads the clock and writes the slot for the forging side to read, and waits on none of its readers (C18).
Each slot it reads the clock, keeps `checkValidClockChange` (the `systemClockMovedBack` trace within 20 s, the stop
beyond it), `startTimeInTheFuture`, and `currentSlotUnknown` with the 60 s backoff, hosts the `ClockWokeEarly`
assertion, and waits for the next slot on the monotonic clock (decision 2). It publishes the slot, or that the slot is
unknown, on a signal, and does nothing else. No forging work can delay it, so the clock checks, and the stop beyond
the rewind limit, run on time even while a forge is slow.

**The slot driver** takes slots from that signal as `knownSlotWatcher` takes them (C1): it handles a slot to its end,
then waits for a slot other than the last one it handled and takes the latest. When it takes the latest, it first
handles, in order, every slot in between that the forging fiber has claimed. Any other slot that passes while it is
busy, waiting for a slow forge for example, gets no line and no count, as the reference skips a slot that passes
during its forging action. While forging is disabled it handles nothing, as the reference runs no forging thread then
(C19).

**One owner per slot.** Each slot has at most one claim, set once and atomically (a compare-and-set on a map from slot
to claim). The claim is either the forging fiber's, made when it starts a forge (below) or at the slot driver's
request, which carries the attempt's final result once it is known (a `Deferred`), or the slot driver's own. Whichever
comes second finds the claim and leaves the slot to its owner. The map also holds the slot driver's resolved-through
mark, in the same atomic update: the highest slot whose outcome it has settled, a slot it passed over while blocked
included. A claim of a slot at or below the mark fails, so the forging fiber cannot claim a slot the slot driver has
already skipped. The slot driver is the only publisher of every slot's outcome, whoever owns the slot, and the forging
fiber waits for it only through the mark below.

**Forging follows slot order.** The forging fiber starts the forge of slot t, and claims t, only once the mark has
reached t - 1. So every slot before t is decided first, and an earlier led slot that the slot driver hands over is the
earliest slot the forging fiber then takes. The invariant: forges, and so their signatures, run in slot order, and the
KES key never moves past a period that an earlier slot may still need. Without it, a forge of a later slot in the next
KES period could advance the key while the slot driver was still deciding an earlier slot; that slot, once found led,
would have no key left for its period (`Sum6KesSigning` refuses to evolve backward), or would find the tip already
past it.

There is no cycle of waits. The forging fiber waits only for the mark, and only before a forge starts, holding neither
the reservation nor the HotKeys lock. The slot driver, at a slot u claimed for forging, waits for u's result; the mark
is then u - 1, so the forging fiber can always start u, and no other forge can hold the reservation, since none of a
later slot can have started. At the first slot of a KES period the slot driver waits on the reservation only while a
forge of that same slot holds it, and that forge waits on nothing the slot driver holds.

The cost is normally none. The slot driver settles slot t - 1 within milliseconds of its onset, before t's wake at t's
onset less the 50 ms margin. That holds while the slot driver's handling of a slot, the wait for a led slot's result
included, stays under the slot length less the margin: 50 ms on the trial devnet, 950 ms on mainnet. When it does not,
on a slow host or after a led slot whose adoption is slow, the early forge of t starts later, when the mark arrives,
which is still correct. If t's slot passes before then, t is passed over with no line and no count, as the reference
skips a slot that passes during its forging action (C1).

For each slot it handles, while forging is enabled, the slot driver:

1. at the first slot of a KES period, makes or reads the period's update (§5.5);
2. if the forging fiber has claimed the slot, whatever the schedule now says, waits for that attempt's final result
   and publishes it: the held lines and counters of the block handed over, or the refusal that stands, or, for a final
   result of not led, what step 4 publishes;
3. otherwise decides the slot: from the schedule if it covers the slot with the inputs in force, and otherwise with
   `Leadership.check` and those inputs, inline, one output evaluation (Q3). A led slot it claims for the forging
   fiber, which forges it at once, and it then waits as in step 2;
4. for a slot not led, claims it (if the forging fiber's claim came first, it goes to step 2 instead) and runs the
   reference's cheap checks that come before its leader check, with no VRF and no ledger tick, in the order `attempt`
   makes them: the tip for `slotIsImmutable` and `blockFromFuture`, the forecast horizon for `noLedgerView`, the era
   and protocol version for Scarab's `forgeFailed`, and the KES key's state for `forgeStateUpdateError`, read from the
   period's update (§5.5). It publishes the line the reference would log if one applies, else `nodeNotLeader`
   (decision 1; [#1101, Q1](https://github.com/KD5UJC-XYZ/scarab/issues/1101#issuecomment-6905164395)). Either way the
   slot is counted in `ForgeEpochStats` (`slotsChecked`) and the about-to-lead counter, and the `ForgingStats` gauges
   are published, every slot, as now (Q2). `startLeadershipCheck` and `stateInfo` are logged only on led slots and at
   a KES period change, where the first slot of the period that the slot driver ends itself logs
   `startLeadershipCheck` first, as the reference orders a slot's lines, whether the period's update succeeded
   (then `stateInfo` follows) or failed (then the slot's ending, the failure's `forgeStateUpdateError` where its
   checks reach the key), once per period and credentials; `stateInfo` at Warning or Alert (seven or fewer periods
   left) is logged in every slot whose checks reach the KES key, without `startLeadershipCheck`, as the reference logs
   it (Q2);
5. checks the forged blocks replaced later (`watch`), as now after each slot.

**The epoch's summary.** Before the first slot of a later epoch, the slot driver publishes the `forgeEpoch` line of
the epoch that ended. It handles slots in order and has waited for every claimed slot before that one, so the line
comes after every slot of the ended epoch it handled has been published and counted, a forge of the epoch's last slot
that ran past the boundary included; meanwhile the clock fiber goes on. Since one fiber publishes every outcome, in
slot order, `ForgingStats` folds them in slot order (Q7).

### 5.5 The KES key at each period

The slot driver makes decision 3's update at the first slot of each KES period it handles, and at the first slot it
handles after a start or after forging is switched on. It takes the key reservation, which waits for any forge in
flight to sign, then calls `HotKeys.update(period)`, which evolves the key and writes it back, or poisons it past its
certificate's window, as the per-slot loop does; the KES gauges are written from the result. A forge holds the same
reservation from its own forge state update (§5.3 step 2) until its block is signed. Whichever of the two takes the
reservation first in a period makes the period's update; the other finds the key already at that period. The old key's
memory is zeroed as now (`Sum6KesSigning`'s `evolve`), so from the period's first slot the key in memory cannot sign
for an earlier period, as on a synced reference node, and the write-back of [ADR
0015](../adr/0015-kes-key-written-back-and-checked-reload.md) replaces the earlier key in its file. Unlike the
reference (§2.6), the key moves even while the node cannot forecast its slots, so it is never older than the clock's
period. It is never newer either: no attempt made before its slot's onset updates the key for a period the clock has
not reached (§5.3), so a led slot in the first slot of a period is forged after the slot driver's update at its onset. A forge that makes a period's
update first, on a late wake or as the first update after a reload has cleared the record, does so once the clock is
in the period. While forging is disabled nothing updates the key, as no forging thread runs then in the reference
(C19).

**One result per period, read by every slot.** The first update of a period records its result: the key's state
(`KesInfo`), or the failure that poisoned the key. The record belongs to the credentials it was made with: a reload
that installs another KES key or certificate clears it, and the next slot the slot driver handles makes the update
again. No check reads the key itself: the KES check of every slot in the period (§5.4 step 4) and the forge state of a
led slot's sequence both take the recorded result, so a slot's outcome does not depend on which fiber reached the key
first. The update comes before the slot's checks, as the reference updates the forge state before its leader check in
the same action (C2, C3). The slot driver, the only publisher, publishes from the record in slot order:

- after a successful update, `stateInfo` at the period change (Q2), and `stateInfo` at Warning or Alert in every slot
  whose checks reach the KES key while seven or fewer periods are left;
- after a failure, `forgeStateUpdateError` with `KESCouldNotEvolve` in the first slot, in slot order, whose checks
  reach the KES key, and with `KESKeyAlreadyPoisoned` in each later one, whichever fiber made the update. That is the
  sequence the reference gives: it updates only in slots that reach that check, the first failure poisons the key, and
  a poisoned key answers every later update with `KESKeyAlreadyPoisoned` (C6). A forge's result carries the recorded
  result, not a rendered line, so the slot driver names the kind when it publishes it.

**The reservation and the locks.** The slot driver and a forge both take the reservation before the HotKeys lock, so
neither can deadlock the other. The slot driver holds the reservation only for the update itself and waits for no
attempt's result while it holds it; a forge holds it from its update to its signature and waits on nothing the slot
driver holds (it waits for the mark only before it starts, §5.4). A reload takes the HotKeys lock alone and does not
wait for the reservation: a reload during a forge changes the credentials, and §5.3 discards that attempt. Without the
reservation the key could move to the next period between a forge's update and its signature; `Sum6KesSigning` refuses
to evolve a key backward, so that block would be lost.

On a slow host, a forge of the last slot of period p that is still running when period p+1 begins keeps the key at p
until it has signed: no forge of a slot in p+1 starts until that slot is settled (§5.4), and the slot driver reaches
p+1 only after it, then evolves the key to p+1. The key reaches p+1 late by the rest of that forge, and slots that
pass meanwhile are skipped (§5.4), as in the reference, where the next slot's update waits for the action under way
and the slots that pass during it are skipped (C1).

### 5.6 What the operator sees

Decision 7 publishes the schedule; the reference keeps it to itself (C10).

| Name | Kind | Meaning |
|---|---|---|
| `scarab_forge_scheduled_leader_slots` | gauge, label `epoch` (`current`, `next`) | The epoch's potential leader slots in the schedule. A slot battle can still lose one, and a fork can change the schedule. |
| `scarab_forge_expected_leader_slots` | gauge, label `epoch` | `slots × (1 - (1 - f)^σ)`, σ the pool's share of the distribution: the expected number over the whole epoch, for comparison, also where the schedule covers only part of it. Display only: computed in floating point and never used for a decision. |
| `scarab_forge_next_leader_slot` | gauge | The next led slot in the schedule; absent when there is none. |
| `scarab_forge_schedule_complete` | gauge, label `epoch` | 1 once the epoch's schedule reaches its last slot, else 0; the scheduled count shows the led slots covered so far (Q8, §9). The schedule covers the epoch from its first slot or, for an epoch computed after it began or a computation that fell behind the clock (§5.2), from the clock's slot where its computation started or resumed, without the slots before. |
| `leaderSchedule` (namespace `Forge`, Info) | log line | When an epoch's schedule is complete, and again whenever a restart changes it: `epoch`, `slots` (the led slots of the range the schedule covers), `expected` (the whole epoch's), and whether the nonce is settled. |

Anyone who can read the node's metrics or logs learns when the pool will lead. That is the purpose; it also means an
operator who exposes `/metrics` publicly publishes the schedule. The metrics page says so (Q9, §9).

### 5.7 Mirror and Deviation

What stays a **Mirror**: the decision of every slot and the certificate of every won slot (C4, decision 9); the forged
block for a given slot, parent, snapshot and credentials; when the block of a led slot in the first slot of a KES
period is built, after the onset (§5.3); the full sequence of traces of a led slot, with the reference's names, order
and severities, and its fields but for the two renderings that differ today (C2, C7, C21); the clock checks (C18); the
KES key's states and its erasure (C6); the forecast check of a led slot (C16).

The **Deviations**, all approved on #1101. A pull request records each as a [REF-7](../design-rules.md#ref-7) row, and
the change that first relies on them adds their rows to the [decisions register](../decisions.md):

| Behaviour | Reference | Scarab | Approval |
|---|---|---|---|
| A slot not led | `StartLeadershipCheck`, `StateInfo` and `NodeNotLeader` at Info, three lines at Debug (C2, C7) | `nodeNotLeader`, or the reference's line where a pre-check fails; `startLeadershipCheck` before it at a KES period change, with `stateInfo` where the period's update succeeded, `stateInfo` otherwise only at Warning or Alert; the counters and gauges as now | decision 1; Q1, Q2 |
| When the leader check runs | at the slot, in the forging thread (C1, C4) | ahead of the epoch, spread over the time left; again at a led slot | decisions 4, 5 |
| When a led slot's block is built | after the slot's onset (C1) | the wake margin before the onset, but after it in the first slot of a KES period (§5.3); handed to chain selection at the onset | decision 6 |
| When the KES key evolves | in `updateForgeState`, at the first slot of a period that reaches the leader check (C6) | by the slot driver at the period's first slot, decision 3's timer; also at a led slot as now, never before the clock's period | decision 3 |
| The schedule | not traced (C10) | three metrics and the `leaderSchedule` line | decision 7 |

The per-slot loop is replaced with no flag (decision 8).

## 6. The numbers

Mainnet: k = 2,160, f = 0.05, 432,000-slot epochs of 1 s slots. The trial devnet (the Antithesis `pool` workload and
the devnet `pool` scenario): k = 5, f = 0.05, 500-slot epochs of 0.1 s slots. One output-only evaluation takes about
1 ms on the profile's JVM; that is an estimate, not a benchmark.

| | Mainnet | Trial devnet |
|---|---|---|
| Randomness window 4k/f | 172,800 slots (48 h) | 400 slots (40 s) |
| Freeze slot, into the epoch | 259,200 (3 days in) | 100 (10 s in) |
| Expected time from the freeze to settlement (k + 1 blocks at f per slot: (k + 1)/f) | 43,220 slots (about 12 h and 20 s) | 120 slots (12 s) |
| From settlement, expected, to the epoch's start ((3k - 1)/f) | 129,580 slots (about 36 h less 20 s) | 280 slots (28 s) |
| Outputs to evaluate per epoch | 432,000 | 500 |
| Evaluations per second, spread from the freeze to the epoch's start | 2.5 | 12.5 |
| The same, from expected settlement | 3.3 | 17.9 |
| The per-slot loop today | 1 | 10 |
| CPU per epoch at 1 ms each | about 432 s (0.25% of a core over 48 h, 0.1% over the epoch) | about 0.5 s (1.25% of a core over 40 s, 1% over the epoch) |
| Expected led slots (decision 7's gauge) | about 22 for a pool with 0.1% of the stake | about 8.5 for a pool with a third |

Three readings:

- **The count of evaluations does not change.** It is one output per slot either way, plus one per led slot for the
  re-check (about 22 or 8.5 an epoch, above). The rate is higher while the schedule is computed because an epoch's work
  falls in the 4k/f window, 40% of a mainnet epoch and 80% of a devnet one.
- **What is saved is the per-slot loop's other work** (about 3% of CPU at 0.1 s slots, #1101) and two of its three
  Info lines per slot, against the clock fiber's and the slot driver's wake and the `nodeNotLeader` line (about 0.3%,
  decision 1). On mainnet, with 1 s slots, the CPU saved is small.
- **What is gained is timing.** The check is off the slot's path, and the block of a led slot is ready at its onset,
  but in the first slot of a KES period (§5.3).

Settlement is a state to report, not a gate. Decision 4 starts the schedule when the nonce is known, and on the
devnet, where k = 5 and Antithesis injects faults, k + 1 blocks may not arrive before the boundary. A schedule from a
nonce that a fork then replaces is replaced by the restart, and a led slot is checked again at the slot (§9 Q10).

## 7. Conformance evidence

Decision 9 names three pieces of evidence. The first, the differential test, is every item below before the devnet
scenario.

- **A differential test of both paths.** The per-slot decision is the `Leadership.check` of today. Over generated
  epochs (lengths, slot ranges across a boundary), nonces (neutral and hashed), stakes (zero, all, between), totals,
  coefficients and VRF keys, the schedule's led slots and certificates equal those of `Leadership.check` run on every
  slot, byte for byte. The wired forger is then driven under `TestControl` with a scripted chain and clock: an epoch
  boundary; a fork that replaces a block before the freeze while the schedule is computed; a fork across the previous
  boundary that changes the mark distribution; a led slot in an epoch's first slot whose tip already holds a block of
  that slot, so that the block is an alternative on the tip's predecessor in the previous epoch and its inputs come
  from that state; slots the clock reaches before the schedule covers them, decided inline (§5.4); a SIGHUP that
  installs another VRF key mid-epoch; forging switched off and on; and a start mid-epoch. For every slot the clock
  reaches, the decision and the certificate it acts on must equal `Leadership.check` with the inputs in force at that
  slot. The early attempt, the KES key and the slot driver have the tests of the items that follow.
- **Tests of the early attempt (§5.3).** Deterministic, under `TestControl`, with one change at each point where it can
  fall: between the wake and the re-check, between the re-check and the signing, and between the signing and the onset.
  The changes are a block of an earlier slot that moves the tip; a fork that changes the nonce or the mark
  distribution; a SIGHUP that installs another VRF key, KES key or certificate; and forging switched off. In each case
  what is handed over at the onset, if anything, equals what the per-slot loop forges at the onset from the node as it
  is then; an early refusal that clears before the onset is forged; a refusal that stands is logged once; and no block
  signed with replaced credentials is handed over.
- **A KES boundary during assembly (§5.5).** Under `TestControl`, a forge of a period's last slot is suspended just
  before it signs while the clock crosses into the next period: the slot driver stays waiting for that slot's result
  (it cannot reach the next period's first slot before the earlier slot is settled, §5.4), the block is signed with the
  key of its own period and handed over, the slot is settled, and only then does the key move to the next period,
  written back once. Contention for the reservation is covered by the expiry test below, with a forge of the same
  slot.
- **Two fibers, one slot (§5.4).** A slot led under inputs A has its early attempt under way when a fork changes the
  inputs to B, under which it is not led, before the onset; run with the forging fiber's claim first and with the slot
  driver reaching the slot first. In both orders the slot has one outcome, `nodeNotLeader`, counted once in
  `slotsChecked`, `notLeader` and the about-to-lead counter, and no block. And when the slot driver claims a slot
  first and the forging fiber then wakes for it, the fiber finds the claim and leaves the slot.
- **A KES expiry boundary (§5.5).** The certificate's window ends at a period's first slot. Run with the slot driver's
update first, with a forge's update first (a forging fiber that wakes late, in the period), and with the slot driver
waiting on the reservation held by a forge of that same slot: `KESCouldNotEvolve` appears once, in the first slot in
slot order whose checks reach the KES key, `KESKeyAlreadyPoisoned` in each later one, and the gauges come from the one
recorded result.
- **A led first slot of a KES period (§5.3, §5.5).** In the differential test, a SIGHUP between the slot's wake and its
  onset installs a certificate issued again for the same KES key, and the certificate's expiry falls on such a slot:
  the reload is taken on both paths and the block is the per-slot decision's, and the key is erased no earlier than
  on the per-slot path. And a pending early attempt made again after a reload of another KES key, the wall clock moved
  back into the period before, then that key's certificate issued again: both reloads are taken on both paths.
- **The epoch's last slot (§5.4).** A forge of an epoch's last slot is suspended across the boundary: `forgeEpoch`
  comes after that slot's outcome and counts it.
- **Forging follows slot order (§5.4).** The slot driver is suspended while it decides slot s, the last slot of KES
  period p, before it hands s over; a scheduled wake for a later slot t in period p+1 comes; then the slot driver
  resumes and finds s led. s is forged and signed first, with the key of period p, and t after it, and t's forge does
  not start before s is settled. Likewise two consecutive led slots across a KES boundary: the first is signed in its
  period, the second's early forge starts once the first is settled, and the key reaches the second period only then.
- **The clock never waits (§5.4).** With the slot driver blocked on a suspended forge, the clock fiber keeps
  publishing slots and its traces on time, a clock moved back by more than 20 s still stops the node, and the slots
  that passed meanwhile get no line and no count.
- **The devnet `pool` scenario**: the reference node's `query leadership-schedule` is its oracle, and every scheduled
  slot is forged or taken by another pool's block. [CONTRIBUTING](../../CONTRIBUTING.md#the-one-scenario-per-area)
  also asks `kes-rotate`, since the KES evolution and the SIGHUP path change.
- **One Antithesis run** of the trial's base configuration (`antithesis/pool`, faults on).

## 8. Stages

1. **S1: the schedule, not wired** (level 1).
   - Scope: the inputs value and its derivation shared with `crossedTo`; the chunked computation over
     `Leadership.decide`; the pacing arithmetic as a pure function; the stream with `switchMap` and the state.
   - The pull request shows: the property that the schedule equals `Leadership.check` on every slot; the pacing
     properties under `TestControl` (done before the deadline; ahead of the clock once caught up; a new value discards
     the old work); no behaviour change, so "None" for Deviations.
2. **S2: forging from the schedule** (level 3: anything forging).
- Scope: the clock fiber, the slot driver and the forging fiber replace the loop; the metrics and the `leaderSchedule`
     line; the answers to §9's questions.
   - The pull request shows:
     - the differential test of §7;
     - [logging](../running/logging.md) with the log golden, [metrics](../running/metrics.md),
       [block production](../architecture/block-production.md), the `block-forging` entry of the compatibility
       register, and the changelog fragment;
     - the REF-7 rows of §5.7 and the decisions register's rows;
     - `pool.py` (its reads of `startLeadershipCheck`, `forge_timing` and `slot_clock`) and `dynamic_forging.py`
       brought in line;
     - `check`, and `pool` with `kes-rotate` run in parallel.
   - Where the build differs from §5, or settles what §5 leaves open:
     - The slot driver derives a slot's inputs from the ledger view forecast from the block the slot would build on,
       with no ledger tick (Q1); a slot not led whose ledger cannot be carried into its epoch so logs
       `nodeNotLeader` where the per-slot loop logged `forgeFailed`, because the reference ticks the ledger to a slot
       only for a slot it leads and Q1 asks for the reference's line. The failure still surfaces, as `forgeFailed`,
       at the first led slot's attempt, which carries the ledger as before, and at the follower's own crossing of
       the boundary.
     - The `stateInfo` of a period change is published at the first slot of the period the slot driver handles,
       whether or not that slot's checks reach the key, so that the KES gauges move at the period's start; on a slot
       not led, after its `startLeadershipCheck`. A period whose update failed has that `startLeadershipCheck` too,
       at the first slot the slot driver ends in it, before the slot's ending line.
     - The KES key's own lines (`kesEvolved`, `kesFileAbsent`, `kesWrittenByRename`, `kesPersistFailed`) are
       written where the key moves, by the slot driver's update of the period or by a forge's, never before the
       clock's period, not held with the slot's lines.
     - A led slot of a KES period the clock has not reached at the wake is not forged ahead: the slot driver hands it
       over at its onset (§5.3, from the review of #1109's final diff). An attempt made before the onset, a pending
       one made again after a reload included, checks the clock just before its update of the key and ends there,
       refused, where the slot's period is later than the clock's (§5.3, from the second review).
     - The slot driver records the period whose `startLeadershipCheck` it published, whichever fiber made the line,
       apart from the KES state's announcement, which drives `stateInfo`: a slot not led logs that line once per
       period and credentials (§5.4 step 4).
     - A held transaction the selection has no verdict on leaves the mempool only when its attempt counts: before
       its block is handed over, or with the refusal that stands; a discarded attempt changes nothing in the
       mempool, so the slot forged again starts from the mempool the per-slot loop would see.
     - The handover re-reads the wall clock after its timer: a clock moved back within the rewind allowance
       delays it to the onset by the clock. The credentials a block was signed with are checked, and the block
       submitted, under the key's lock, so that no reload lands between them; a block whose credentials were
       replaced or whose forging was disabled is not handed over and logs one Debug line, as the reference's
       forging thread of replaced credentials ends (C19).
     - While forging is disabled the slot driver still claims each slot, so that a forge claimed by a reload's
       forging fiber in the meantime is awaited and published before the mark passes it.
     - The forging fiber forges a slot only once the clock can know it (the era history's horizon), as the
       reference's watcher hands it only known slots; a forging fiber woken before the clock has published a slot
       waits for the slot driver's mark.
     - The schedule of an epoch the inputs no longer name is dropped, so that forging switched off leaves none.
     - `Forger.Point` names the points where the tests of §7 suspend a fiber; `Forger.step` keeps the per-slot
       decision of one slot as the differential test's oracle.
3. **S3: the evidence** (level 4).
   - Scope: the devnet `pool` run and the one Antithesis run, recorded in the evidence log with commit, date and
     command.
   - The pull request shows this document's "as built" section, and its status moved to Current.

## 9. Questions the draft raised, and their answers

Reading the code turned up questions the decisions of §4 did not settle. The owner answered those that change what an
operator sees beyond the approved rows of §5.7 ([REF-4](../design-rules.md#ref-4), [REF-5](../design-rules.md#ref-5);
recorded on [#1101](https://github.com/KD5UJC-XYZ/scarab/issues/1101#issuecomment-6905164395), 2026-11-16 01:55 UTC);
the lead settled the internal ones.

- **Q1 (owner). A slot not led whose reference trace is not `NodeNotLeader`.** The slot driver runs the reference's
  cheap pre-checks and logs what the reference logs (`slotIsImmutable`, `blockFromFuture`, `noLedgerView`,
  `forgeStateUpdateError`, and Scarab's `forgeFailed`); `nodeNotLeader` only when none applies (§5.4).
- **Q2 (owner). Counters and gauges on slots not led.** Updated every slot as now (`slotsChecked`, the about-to-lead
  counter, the `ForgingStats` gauges). `startLeadershipCheck` and `stateInfo` at Info only on led slots and at a KES
  period change; `stateInfo` at Warning or Alert every slot, as the reference logs it (§5.4).
- **Q3 (lead). Slots not yet covered.** The slot driver runs the per-slot decision inline (`Leadership.check` with the
  inputs in force, one output evaluation) and hands a led slot to the forging fiber (§5.4).
- **Q4 (owner). Forging ahead, then again.** The `forgedBlock` line and the forge counters are held until a block is
  handed to chain selection; a discarded attempt logs one Debug line (§5.3). The snapshot taken up to the margin
  earlier is part of decision 6.
- **Q5 (lead). Adoption at the onset.** S2 hands the block over at the onset, so our own validation of it runs after
  the onset, as Amaru's design does. S2 measures the time from the onset to the adoption; a two-step `Follower` API
  (validate before, commit at the onset if the tip has not moved) is a follow-up only if that time matters.
- **Q6 (lead). The wake margin.** 50 ms, a constant, with no flag (§5.3).
- **Q7 (lead). Trace order across fibers.** The `ForgingStats` fold takes the order of the slots, not of the lines'
  arrival, so a late line cannot book a negative gap in `slotsMissed`. The slot driver, the only publisher, publishes
  the slots' outcomes in slot order (§5.4).
- **Q8 (lead). Gauges while computing.** `scarab_forge_scheduled_leader_slots` shows the led slots found so far, and
  `scarab_forge_schedule_complete` says when the epoch is covered (§5.6).
- **Q9 (lead). Exposure.** The metrics page says that whoever reads `/metrics` or the logs learns when the pool will
  lead; exposing them is the operator's choice (decision 7).
- **Q10 (lead). Settlement.** Reported only, in the `leaderSchedule` line; it changes no behaviour. A rollback past the
  freeze recomputes the schedule whether or not it was settled.
- **Q11 (lead). Timing measures.** They stay measured from the slot's onset, the meaning an operator reads them in:
  `adopted`'s `latencyMs`, `scarab_forge_adoption_seconds` and `forgeLatencyMs` from the onset to the adoption. A
  `forged` time before the onset is negative relative to it, which is correct. The `pool` scenario's `toForged` and
  `slot_clock` are adjusted in S2 to read the handover at the onset.

Risks:

- A loaded host wakes late. The same evidence log records timer lateness of 69 to 134 ms in devnet `pool` runs with
  Scarab on one CPU, more than the 50 ms margin; on such a host a led slot's block is ready after its onset, as the
  per-slot loop's is today.
- The re-check of decision 5 runs only on slots the schedule leads. A slot the schedule omits through a fault of its
  own, with the inputs in force, would be logged `nodeNotLeader` and not forged; the differential test of §7 is what
  guards against that.
- An era boundary at the next epoch (Conway to a later era) changes the derivation of the inputs. Scarab forges only
  in Conway (`forgeFailed` otherwise), so this waits for forging in later eras
  ([#544](https://github.com/KD5UJC-XYZ/scarab/issues/544)).
- When the forging area moves to the `11.2` build (#1002), the claims of §2.9 are to be read again at its pins; they
  were checked at 808e4005 and 29a7d656 for this document.

## 10. What does not change

- **No persisted format changes.** Nothing is written but the KES key's write-back, as now.
- **The blocks.** For a given slot, parent, snapshot and credentials, the bytes are the per-slot loop's.
- **The forge path after the leader check**, its refusals (`forgeFailed`, `forgeDefect`), and dynamic block forging
  (SIGHUP, `--start-as-non-producing-node`).
- **Which node forges.** A node without credentials runs no forger, so no clock fiber, slot driver or schedule, as now.

## 11. As built

What [#1109](https://github.com/KD5UJC-XYZ/scarab/pull/1109) built, in the order of §5. Where the build settles what §5
left open, or differs from it, §8 lists the point under S2; it is not repeated here.

- **The inputs (§5.2).** `EpochInputs` is an epoch's leader-check inputs as one value, compared by value: the epoch and
  its slots, the epoch nonce, the pool's stake and the total, the active slot coefficient, and the VRF key by its
  public key. `Forger.inputsAt` derives it from a tip through `Forger.conwayAt` and `Forger.leaderView`, the functions
  the slot driver's own check of an uncovered slot calls (Q3), so the two paths cannot be given different inputs.
- **The schedule (§5.2).** `LeaderSchedule` walks an epoch in chunks of `LeaderSchedule.ChunkSlots` (100) slots with
  `Leadership.decide`: the output for every slot, a proof for a led slot only. `LeaderSchedule.Pacing` sets the pause
  after each chunk. The stream switches to each new value of the inputs' signal and drops the work under way; the state
  answers for a slot only while the inputs it was computed from are those in force, and drops an epoch the inputs no
  longer name. It is held in memory only.
- **The fibers (§5.1, §5.3, §5.4).** `Forger.run` starts the scheduler, the clock fiber, the forging fiber and the slot
  driver in one scope and joins them at once ([FIB-3](../design-rules.md#fib-3)); the first `Fatal` ends all four and
  is returned as before. `Forger.step` keeps one slot's per-slot decision, as the oracle of the differential test.
- **The KES key (§5.5)** is updated under one reservation, from a forge's update through its signing; the slot driver's
  update at a period's first slot takes the same reservation.
- **What the operator sees (§5.6):** the four gauges in [metrics](../running/metrics.md), and the `leaderSchedule`
  and discarded-attempt lines in [logging](../running/logging.md).
- **The tests (§7).** `LeaderScheduleSuite`: the schedule's decision and certificate equal `Leadership.check` on every
  slot of generated epochs, and the pacing's arithmetic. `LeaderScheduleStreamSuite`: the stream under `TestControl`.
  `ScheduledForgerSuite`: the differential, each slot's outcome and each block handed over against `Forger.step`, across
  an epoch boundary, forks past the freeze and across the previous boundary, a VRF key reload, forging switched off
  and on, a start mid-epoch, a led first slot of a KES period with a reload in its wake margin and with the
  certificate's expiry, and a pending attempt made again after a reload with the clock moved back a period. `ForgeInterleavingSuite`: the interleavings §7 lists, each placed with `Forger.Point`.
- **The evidence (S3)** is in the [evidence log](../compatibility/evidence-log-5.md), the entries for #1101: the
  devnet `pool` and `kes-rotate` runs, and one Antithesis run of the trial's base configuration under node, clock and
  network faults, in which every property that must never fire held and forks past k came at the per-slot forger's
  rate. The Antithesis run, and the devnet `pool` run beside it, were at 04b6b8447, before the fixes of the review of
  the final diff: the first slot of a KES period forged at its onset, and `startLeadershipCheck` at a period change.

What the measurements say about §6 and §9:

- **The CPU.** As §6 reads it, the count of evaluations does not change, and on the trial's topology a local comparison
  (2026-11-16, the node beside two Haskell pools at 0.1 s slots, two 300 s windows each) found no difference in the
  node's CPU between the scheduled forger and the per-slot loop beyond the spread between runs. The gain is timing.
- **The timing (Q5, Q11).** On the devnet `pool` runs a led slot's block was built a median 44 to 46 ms before its
  onset and adopted a median 10 to 12 ms after it. The time from the onset to the adoption is small, so the two-step
  `Follower` API of Q5 is not needed.
