# Operational private Scala validator milestones

The immediate goal is an ordinary runnable validator on an isolated Cardano
development network. The current profile is supplied, source-bound PV9/header11.2
prestate with supported ADA/native transactions. Unsupported features stop with a
typed outcome. No milestone below implies full ledger or consensus validation.

## 1. Runnable bounded node — implemented

Provide `node` with strict explicit profile/bootstrap/loopback-peer configuration,
resource-owned startup and cancellation, structured progress and terminal status.
Reuse the existing bounded engine; do not require a reference post-oracle to run.
Accept when configuration fails before I/O, bootstrap provenance is visible,
announced/fetched cursors never masquerade as applied state, unsupported/rejected
blocks stop, and cancellation/failed startup releases owned resources. A budget
stop is not a caught-up claim. Focused scripted and retained-input tests plus
source review gate this feature. A reviewed isolated integrated case gates the
runnable-node milestone.

## 2. Sustained same-epoch operation — first integrated case passed

Continue validated progress beyond the initial eight-block acceptance window with
explicit resource, memory and retained-rollback bounds. Define eviction and
outside-window behavior before enabling it. Accept when bounded memory and exact
original-byte/state continuity survive sustained progress, permitted reconnects
and retained rollbacks; unsupported epoch transitions stop. Do not infer
availability from ambiguous protocol failures. The node lane owns no window
internals and must not loop independent bootstrap sessions to simulate continuity.
Compaction must preserve derived checked-anchor identity and rebind receipts. The
current durable format is incompatible with compacted windows until separately
designed. The integrated live scenario must exceed eight blocks within a
sufficiently long same-epoch window; do not silently change limits or stitch short
fixtures. The separately reviewed twelve-block volatile case has now passed; see
[the operational acceptance record](private-node-acceptance.md). No automatic
follow-up run is authorized by this plan.

## 3. Durable restart and fork handling — separate worker

Adapt the reviewed durable coordinator behind the node engine boundary, preserving
atomic publication and exact checkpoint identity. The existing offline restart
adapter and source/classpath/input pin are preparation, not restart evidence.
Accept graceful restart only after separately granted retained-input execution;
process-kill/write-interruption and live competing-fork cases require their own
evidence. Distinguish retained rollback, outside-window rejection and unsupported
fork handling. No power-loss claim follows from ordinary close/reopen tests.

## 4. Epoch continuity — later milestone

Fixed registration does not imply fixed stake fractions: local devnet rewards
require snapshot rotation and reward/account state. Stop at epoch boundaries until
these transitions are implemented. Specify and implement the missing epoch transitions, stake/registration changes,
nonce evolution and protocol changes before extending the same-epoch profile.
Accept only with independent reference comparisons across those transitions and
explicit negative cases. Endpoint equality never proves continuous invariants.

## Worker interface and ownership

The runnable-node lane owns `NodeCommand`, its suite, `Main` dispatch and this plan.
Sustained and durable workers own their respective engine/coordinator internals.
The initial adapter boundary is:

```scala
trait Engine:
  def snapshot: IO[CoherentSequence.Snapshot]
  def run: IO[BoundedValidatorRunner.Outcome]

trait EngineFactory:
  def resource(
      context: SequenceInput.Context,
      peer: Resource[IO, BoundedChainFollower.Peer[IO]],
      policy: BoundedValidatorRunner.Policy,
      onTransition: String => IO[Unit]
  ): Resource[IO, Engine]
```

Transitions after publication/rollback read the committed snapshot. Acquisition
cursors remain separate. The resource factory owns finalization; status must not
serialize fences or resumable authority. The first factory delegates to the
existing bounded in-memory runner. Workers should propose shared type changes
before expanding this boundary; a sustained engine may need a dedicated policy
and outcome rather than pretending the bounded target changed semantics.

The separate durable worker's backend seam sits beneath this engine boundary:
`snapshot`, `prepare`, `publish` and `rollbackTo`, with opaque candidates and
immutable snapshots that distinguish volatile state from acknowledged durable
state. The durable backend owns expected tokens and delegates to `DurableRuntime`.
Storage failure terminates using its cached last acknowledged state; neither the
node nor the engine may recover status by reading a poisoned backend. The node
does not implement this backend or convert status identifiers into authority.

The sustained worker proposes `Runtime.advanceAnchor(fence, through)` returning
an atomic `Snapshot`, plus state `compactedBlocks`, `derivedAnchorId` and
`depth = compactedBlocks + retained originals`. Compaction through the current tip
retains a checked applied tip. Opt-in `Policy(advanceWindow = true,
rollbackCapacity = 1..8)` leaves existing bounded defaults unchanged. Rebound
receipts and constant-size provenance commit the checked boundary while retaining
the original context identity. These are worker integration requirements, not
capabilities of the current node command.

`ValidatedCheckpoint` v1 must reject every derived-anchor state, including an
empty retained suffix. There is no durable compaction API. Sustained volatile and
bounded durable demonstrations are separate; a sustained-plus-durable node
configuration must fail explicitly until compacted persistence is designed.

## Integration cadence

Run focused tests and independent source review per feature. Run full regression,
hosted CI and reviewed live cases at integrated milestones. Independent development
continues while CI runs; check it at natural checkpoints and fix actual failures.
Keep caches/output private per worker. Preserve failed attempts and all existing
private evidence. Public commits contain reviewed source and scoped summaries,
never keys, raw provider exports or cluster state.
