# Explicit coherent rollback-window advancement

`CoherentSequence.Runtime.advanceAnchor(fence, through)` advances only to a point
already checked by this runtime and present in its retained window. The fence is
checked atomically with publication. Foreign/stale fences and points outside the
window fail without changing state. Advancing to the current anchor is a no-op.

The certificate anchor is the exact existing checked certificate state at the
boundary. Every retained undo before-state is rebased, and each after-ID points to
the next rebased state. Nonce, certificate and ledger undo evidence is preserved.
The current checked tuple, eligibility and ledger revision do not change. Old
candidates and fences become stale because state identity includes the changed
acquisition window and derived provenance.

Read-only `State.compactedBlocks`, `derivedAnchorId`, and `depth` distinguish this
anchor from the original supplied context. `depth` is branch depth from that
context, not a lifetime event counter. Rolling back decreases the retained suffix
only. Compacting through the current tip retains `scopedAppliedTip`, even with an
empty suffix. Provenance is a fixed-size hash of the checked boundary identity and
count; it includes the previous provenance through that identity. No discarded
coherent state or receipt chain is retained. Counts are bounded by ledger revision
limits, with at most eight originals and eight undo receipts retained.

This is an explicit availability policy, **not finality**, a security parameter,
chain selection, or authentication of a new supplied snapshot. Rollbacks below the
derived anchor fail. The original context/profile identity, same-epoch restriction,
and restricted ledger/transaction scope remain. Full-ledger and consensus flags
remain false.

## Runner API

`BoundedValidatorRunner.Policy(advanceWindow = true, rollbackCapacity = 8)` opts
into dropping one oldest checked prefix when the retained window is full. Capacity
is 1..8; the target is current derived depth, bounded by 256 and the event budget.
Default `advanceWindow = false` preserves stop-at-capacity behavior. Existing
event, byte, time and reconnect budgets remain nonrefundable and unchanged.

The runner advances after fetching/parsing the next block and before cryptographic
preparation. Cancellation or rejection afterward can leave the window compacted,
but never publishes that unvalidated block or changes the checked tuple, tip or
ledger revision. This deliberate loss of rollback availability is part of the
opt-in policy. Reconnect offers only the retained points and derived anchor.

## Durable boundary

`ValidatedCheckpoint` v1 replays all originals from the original supplied context.
It **cannot recover a compacted window**. Encoding any compacted state explicitly
fails, including an empty suffix or rollback to the derived anchor. The durable
facade exposes no anchor-advancement method. A future format requires a separately
reviewed checked derivation/recovery contract; serializing a derived tuple as an
unverified supplied snapshot is not supported.

## Verification limits

Focused offline tests use existing original signed empty and multi-transaction
captures. They cover compaction, repeated rollback/reapplication, tuple identity,
stale capabilities, checkpoint exclusion and cancellation after advancement.
These short captures do not establish more than eight distinct forward blocks.
That integration/live scenario remains with the coordinating lane. No live cluster
or public-chain fetch is launched by this packet.

Focused verification: `app/testOnly lab.CoherentSequenceSuite
lab.BoundedValidatorRunnerSuite lab.ValidatedCheckpointSuite` passed **56 tests**
with `SEQUENCE_FREEZE_EVIDENCE=/freeze` and
`COHERENT_SEQUENCE_EVIDENCE=/sequence`, using the retained
`cardano-nonce-freeze-live1-20261009` and `cardano-live-runner-live2-20261009`
directories mounted read-only. Docker used no network, two CPUs, 2 GiB memory,
and a worktree-private build/cache. `scalafmtAll` and `git diff --check` passed.
No greater-than-eight-distinct-block or multi-receipt-after-compaction live result
is claimed by this packet.
