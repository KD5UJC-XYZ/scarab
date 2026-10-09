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
Compaction must preserve derived checked-anchor identity and rebind receipts. The original bounded durable v1 format remains incompatible with compacted windows. The separate sustained-durable v2 mode provides journal-bound compacted persistence. The integrated live scenario must exceed eight blocks within a sufficiently long same-epoch window; do not silently change limits or stitch short fixtures. The reviewed twelve-block volatile case remains recorded in [the operational acceptance record](private-node-acceptance.md). The separate [adaptive sustained-durable acceptance](adaptive-sustained-acceptance.md) now demonstrates twelve original blocks with capacity two, process A at depth nine, exact full-claim/projection recovery by process B, and live continuation to depth twelve. Its original-body inclusion and complete supported reference-projection checks passed. Later reference-chain growth does not establish Scala epoch continuity. No automatic follow-up run is authorized by this plan.

## 3. Bounded durable restart — first live case passed

Adapt the reviewed durable coordinator behind the node engine boundary, preserving
atomic publication and exact checkpoint identity. The separate retained-input graceful and post-acknowledgement SIGKILL cases
passed at the e7d1e6f source pin; see the acceptance record for their exact scope.
They do not demonstrate live network continuation. The ordinary-node create/resume case now demonstrates newly acquired live blocks
after strict recovery of an externally pinned receipt; its four-block scope is
recorded in the acceptance document.
Live competing-fork and write-interruption cases still require their own evidence. Distinguish retained rollback, outside-window rejection and unsupported
fork handling. No power-loss claim follows from ordinary close/reopen tests.

## 4. Epoch continuity — later milestone

Fixed registration does not imply fixed stake fractions: local devnet rewards
require snapshot rotation and reward/account state. Stop at epoch boundaries until
these transitions are implemented. Specify and implement the missing epoch transitions, stake/registration changes,
nonce evolution and protocol changes before extending the same-epoch profile.
Accept only with independent reference comparisons across those transitions and
explicit negative cases. Endpoint equality never proves continuous invariants.

Reviewed foundations now include [atomic in-memory stake publication and undo](atomic-stake-coordinator.md)
and an [unpublished pure boundary preview](conway-epoch-boundary-preview.md).
The preview accepts explicitly supplied synthetic reward effects, rotates snapshots
after reward application, and retains old-mark leadership and pre-tick reward inputs.
Unknown and pulsing reward phases fail closed. These are partial foundations:
runtime epoch guards and stake-bearing checkpoint rejection remain. Native reward
calculation, admitted branch ancestry and complete epoch-state transitions still
require their own implementation and conformance evidence.

The pure foundation now composes checked supplied parameter/global projections
into [reward-start allocation](conway-reward-start.md), pool/leader and member
calculations, completion deltas and application-time recipient filtering.
The [immutable monetary pulser](conway-reward-pulser.md) shares this arithmetic
and exposes bounded progress, delayed completion after exhaustion and forced
completion. Its strict monotonic signal API is a local restriction; old immutable
states remain replayable. Native provenance/parity, events, non-myopic updates
and runtime epoch transitions remain outside this milestone. The next evidence
priority is an offline native differential, not further pure abstractions.

The [internal synthetic successor-block path](synthetic-successor-block.md)
checks boundary-plus-block composition and atomic rollback under explicit omitted
effect assumptions. It is package-private and tested with deliberately synthetic
anchor geometry, not native boundary/valid-chain evidence. Public epoch guards
and checkpoint refusals remain. Future recovery needs authenticated historical
boundary and freeze provenance plus measured aggregate limits; it cannot infer
historical component identities from final revision minus retained depth.

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
Storage failure terminates using its cached last confirmed state, which may be
only loaded-verified and may be older than disk. Neither the node nor the engine
may recover status by reading a poisoned backend. The node
does not implement this backend or convert status identifiers into authority.

The integrated sustained runtime provides `advanceAnchor(fence, through)` returning
an atomic `Snapshot`, plus state `compactedBlocks`, `derivedAnchorId` and
`depth = compactedBlocks + retained originals`. Compaction through the current tip
retains a checked applied tip. Opt-in `Policy(advanceWindow = true,
rollbackCapacity = 1..8)` leaves existing bounded defaults unchanged. Rebound
receipts and constant-size provenance commit the checked boundary while retaining
the original context identity. These capabilities remain available in the sustained volatile node command. The original bounded durable backend and `ValidatedCheckpoint` v1 still reject derived-anchor state, including an empty retained suffix. The separate sustained-durable v2 path adds acknowledged compaction, a controller journal and exact full-claim recovery; it does not make v1 checkpoints compatible with compacted anchors. The [adaptive acceptance record](adaptive-sustained-acceptance.md) scopes the successful combined sustained and durable case, including graceful process handoff and independent reference comparison. It adds no power-loss guarantee.

## Integration cadence

Run focused tests and independent source review per feature. Run full regression,
hosted CI and reviewed live cases at integrated milestones. Independent development
continues while CI runs; check it at natural checkpoints and fix actual failures.
Keep caches/output private per worker. Preserve failed attempts and all existing
private evidence. Public commits contain reviewed source and scoped summaries,
never keys, raw provider exports or cluster state.


## Bounded durable integration

The approved session-bound `ValidatorTransitions` backend, runner and CLI are
integrated. The independently reviewed bounded graceful restart case passed;
this does not extend the durable format to compacted anchors.

Use explicit create/resume selection, a checkpoint store, a private receipt
directory and the expected context commitment. Resume additionally selects one
bounded acknowledged receipt by exact path and SHA-256; it never discovers a
token from disk or falls back to another checkpoint. Pending-token records are
separate from returned acknowledgements. Loaded-verified recovery and no-op
rollback cannot manufacture a new acknowledgement. Receipt write failure is
terminal and does not justify retrying a publication.

The bounded durable runner verifies context and restored capacity before opening
a peer. Its target is cumulative chain depth rather than publication revision.
A target already reached requires no peer. It owns the backend outside the peer
lifetime and keeps opaque views/prepared candidates private. Ordinary status
contains confirmation classification and token-free state; explicit private
receipt artifacts carry restart authority.

The reviewed live case ended process A at depth two, pinned its returned receipt,
then started process B with that exact receipt and verified new live continuation
to cumulative depth four in the same epoch. It also captured raw serialized ledger
state with matching tip and protocol/configuration evidence inside stable paused
brackets and their existing deadlines. Native epoch-decoder compatibility remains
a separate check.
JSON `possibleRewardUpdate: null` cannot establish an absent reward update.
No extra pause, deadline extension or retrospective alteration of the completed
twelve-block evidence follows from this plan.
