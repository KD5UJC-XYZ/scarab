# Proposal: one in-process v2 durable backend

Status: approved design; the private Session backend is implemented as documented
in `combined-local-v2.md`. Runner/CLI wiring remains a later integration patch.
The text below records the original design/API proposal. The reducer and local journal adapter are
approved prerequisites; this document does not wire the node, runner, store or
runtime. It targets one controller owner and one writer resource for a trusted
local process. Process spawning, external supervisors, migration and automatic
initialization retry remain unsupported.

## Smallest public surface

Keep `ValidatorTransitions.Backend` as the runner's only mutation interface.
Add one private owned backend variant and v2 confirmation values carrying the
complete `LocalDerivedCheckpoint.Claim`. Do not cast a v2 token to the existing
v1 `ValidatedCheckpoint.Token`, or broaden `tokenOption` to conflate them.
The v1 and volatile factories keep their current behavior.

Proposed signatures, with names illustrative:

```scala
private[lab] object CombinedLocalV2:
  final case class Config(
    binding: ControllerJournalCodec.Binding,
    capacity: Int,                       // 1..8
    recoveryDeadline: FiniteDuration     // positive, bounded by configuration
  )
  final case class Bootstrap(
    context: SequenceInput.Context,
    originals: Vector[BoundedChainFollower.Original], // 1..capacity, byte bounded
    compactThrough: ChainSync.Point      // explicit checked prefix boundary
  )
  def create[F[_]: Async](config: Config, seed: Bootstrap): Resource[F, Session[F]]
  def resume[F[_]: Async](config: Config): Resource[F, Session[F]]

// Package-internal Session, held only by ValidatorTransitions:
// snapshot, prepare, publish, rollbackTo, advanceAnchor, lastConfirmed, close.
// Mutations require the current opaque view and exact complete claim.
```

`ValidatorTransitions.localV2Create` and `localV2Resume` wrap this session; add
`Backend.advanceAnchor(expected: View, through: Point): F[Result[View]]` using the
same foreign-session/stale-view checks as publish and rollback. No controller,
raw runtime, store disk handle, force/evidence submission, accepted authority,
mutable cell or proposed snapshot escapes. Failure reporting remains a cached
last-confirmed value that may be older than disk.

The existing `Runtime.exportLocalCheckpoint` is insufficient for a durable
transition: calling `publish` or `advanceAnchor` first would mutate memory before
intent persistence. Add narrow private helpers inside `CoherentSequence` that
plan a transition from its owned `Cell`, export the proposed cell's owned anchor
and suffix, and install the exact planned cell after acknowledgement. Reuse the
existing pure publish/rollback/advanceAnchor functions. A plan binds runtime
owner, predecessor fence, predecessor complete claim and proposed cell, and is
usable only under the combined mutation gate. Do not expose a generic arbitrary
cell import/export API or a user-constructible plan.

## Bootstrap: v2 does not represent the un-compacted seed

`LocalDerivedCheckpoint.encodeOwned` requires `compactedBlocks > 0`. Consequently
the new factory cannot simply serialize `CoherentSequence.create` as generation
zero, and must not quietly weaken that requirement.

For the first implementation choose the explicit bounded `Bootstrap` above:
privately construct a runtime from the pinned context, parse and fully validate
each supplied original through existing prepare/publish, explicitly compact the
requested nonempty checked prefix, and export generation zero. Keep the runtime
unpublished throughout. Reject an empty seed, excess count/bytes, wrong context,
invalid block, unknown compaction point or depth zero. There is no live network
acquisition inside this factory. Callers can obtain the bounded originals through
the existing acquisition workflow, but the factory revalidates them.

This avoids transferring an externally held mutable Runtime. A future optimized
owned handoff would need atomic donor revocation and exclusive transfer; ordinary
export does not revoke the donor and is not such a handoff. Supporting generation
zero before any compaction or automatic v1 migration is outside this increment.

Ordinary `resume` never bootstraps a missing checkpoint. Initial Pending(None→B)
with no primary image can be reconciled to aborted Dormant, but the factory then
returns an initialization-required outcome. An explicit subsequent Create
continuation with a new bootstrap plan must be separately specified; it is not
an automatic retry hidden in Resume or a relaxation of current journal Create.

## Ownership and in-process launch settlement

Acquire the controller journal lock first and keep it through writer allocation,
all callbacks, publication, and teardown. Release the writer/store lock first and
the journal lock last. All writer allocations are structured resources owned by
this scope; no detached fiber, cancellation timeout that abandons an acquisition,
or asynchronous release may survive it. The logical child ID identifies the
local writer handle, not a PID or a claim of external process supervision.

For a new empty journal: force `ReserveLaunch` before allocating the writer.
Handle `LaunchExact` by exactly one resource allocation for that identity; force
`ChildBound`, then obtain the exact store lock and report `LockHeld`. Neither the
allocated handle nor the Probe runtime is returned to the caller.

If allocation is canceled, settle it by joining acquisition and any finalizer.
A late successful acquire must be finalized before reporting absence or exit.
Force retirement before settlement. Report `Absent` only after the outstanding
allocation request cannot complete; report `Exited` only after the exact handle
has closed and its lock is released. If settlement cannot be established, retain
Retired/unended, fail closed, and keep the owning resource from granting another
lease. Do not manufacture settlement from a canceled fiber request, timeout or
failed `tryLock`. A blocked local filesystem call may delay shutdown; there is no
safe deadline-based ownership release while that call can still complete.

Normal close takes the mutation gate, forces retirement when the journal is
healthy, settles the local writer physically, records ownership end, and closes
the journal last. If journal persistence is already poisoned, it cannot record
another successful transition: still join/finalize the writer before releasing
the journal lock, leave the durable lease unresolved, and require strict recovery
on the next open. Physical settlement permits resource release; failure to record
settlement does not permit same-session reuse or a fabricated completion.

For recovery of a persisted old lease: force Retire, then acquire the checkpoint
lock as a quarantine guard, without constructing a new writer or granting write
permission. Successful journal and store ownership plus the structured-lifetime
contract establish that the old local writer and its allocation scope have ended.
Record settlement for the exact old logical handle. Keep the guard continuously
held; after forcing a fresh lease, adopt this guard into its writer resource and
report its exact binding/lock. There is no unlock/relock gap and no second writer.

A crash after forced ReserveLaunch but before initial store allocation may leave
no checkpoint directory or lock. Strict Resume must return initialization-required
without creating either, declaring the old launch settled, or granting a writer.
Keep the journal lease unresolved. Recovery from that initial allocation cut
requires a separately specified explicit initialization-continuation action;
this first Resume API does not promise automatic recovery for that case.

This inference is valid only for journals owned exclusively by this combined
resource protocol. Before implementation, pin a small immutable launch-policy
tag such as `in-process-resource-v1` in the journal binding/codec (version the
journal encoding accordingly). Reject unmarked/other-policy journals on this
factory's Resume. This is one protocol discriminator, not a supervisor registry.
It avoids claiming that taking a lock settles some unrelated external supervisor's
still-pending create request. Existing external/migration lease adoption is
unsupported. Keep the adapter's existing general pure values unwired elsewhere.

## Exact store inspection and authority

Use a small v2 byte-store resource patterned after `NioValidatedCheckpointStore`:
bounded regular files, no symlinks/FIFOs, lifetime file lock, exact predecessor
bytes/claim CAS, CREATE_NEW staging, checked writes, file force, mandatory atomic
replace, directory force, and poisoning after uncertain publication. Do not pass
v2 bytes into the v1-only store, or make a generic pluggable storage framework.
No lock-free inspection or checkpoint path supplied by a message is accepted.

Under both locks, read only the primary image and decode it as untrusted. Compare
the complete decoded claim with the registry entry selected by the reducer's
`Inspect` effect. Allowed values are exactly acknowledged A, or pending A/B;
absence is allowed only for initial pending/no-image reconciliation. Reject
another generation, store, context, issuer, policy, anchor, final ID, depth,
revision, or missing/unknown primary. A valid temporary file is never promoted.

The internal `ControllerAuthority` implementation authorizes only the exact
currently requested Verify claim while its lease, controller revision, registry
entry and physical store lock are unchanged. Pending successor B can be accepted
for verification because its forced intent came from the exclusive checked local
writer; that acceptance is not a committed checkpoint or Serving permission.
Call `LocalDerivedCheckpoint.accept` and bounded `recover` in that frozen scope;
discard the accepted capability afterwards. Never cache it across a transition,
restart or new session. Recovery rebuilds fresh owner-bound suffix receipts.

Return `Verified` only after full exact-claim decoding, context reconstruction
and suffix replay succeed. Resolve/force the journal outcome, then force Activate
before exposing the reconstructed session. Current session and a recovered claim's
issuer need not match: after restart the next publication has the new session
issuer and generation A+1; it must not rewrite A merely to replace its issuer.

## Mutation ordering and observable state

Hold the combined gate for snapshot, prepare, publish, rollback, anchor advance
and close; acquire inner journal/store gates only in that order. Evidence handling
never calls back into the outer gate. Existing runner facade gating may remain
outside it, with the same fixed lock order.

1. Check Serving/healthy state, exact view/owner/fence and predecessor full claim.
   Purely plan the next cell and encode its bounded complete v2 publication.
2. Force `Begin(operation A→B, full B)` and require exact `Prepared`. No checkpoint
   byte is changed before this reply. Guard counter exhaustion before proposing.
3. Install B against exact A under the held store lock; file force, atomic replace
   and directory force must all succeed. Use absence CAS only for explicit initial
   publication. Require decoded B to match the complete proposed claim.
4. Submit exact `Installed`, then satisfy its `Verify` effect by reading the actual
   primary bytes and performing bounded accepted-authority recovery. A private
   verification runtime may be discarded; it never replaces the proposal owner.
5. Submit `Verified(B)` and require the resulting exact forced `Committed(B)`.
6. Install the planned cell and update the session's full claim, current view and
   reporting cache as one gated completion. Only now return success. For initial
   publication/recovery, force Activate before exposing the session at all.

No public snapshot, report, candidate or successful return can observe the new
cell between these stages. A post-commit failure before memory installation
poisons the session; resume uses committed B. Any failure after durable intent
that leaves publication uncertain poisons and releases no additional mutation
permission. Do not continue using A just because the caller did not get a reply.

True no-ops preserve the exact cell/claim and generation after checking current
Serving permission. Anchor advance is not identified as a no-op by revision
alone: current code changes compaction metadata/identity while ledger revision
can stay unchanged. Any changed cell needs a new checkpoint generation and
controller operation.

Once intent persistence starts, keep the command's write/commit/memory phase
uncancelable until known outcome. Gate waiting and bounded pure planning can be
cancelable before intent. Cancellation during bounded verification after disk
publication may terminate the session, leaving Pending for restart, but cannot
reopen the gate to a live writer. Prefer a masked mutation with an internal
bounded verification timeout that poisons on failure. A pending cancellation at
the end may discard a successful reply; the persisted commit remains authority.

## Making the runner's rolling window durable

The existing `BoundedValidatorRunner.makeRoom` calls volatile `Runtime.advanceAnchor`.
Do not reuse it against an exposed runtime. Add the corresponding narrow
`makeRoom` policy to the backend-based durable runner: when its confirmed window
is full, preserve the current fetch/parse order, then call
`Backend.advanceAnchor(currentView, oldestCheckedPoint)` and wait for its
acknowledged new view before preparing the next publication. A failed fetch or
parse must not discard retained rollback history merely to make room early.
The explicit availability policy remains: history before the new anchor is no
longer rollback-available; this is neither finality nor extra consensus validation.

Compaction and the following block publication are two separate durable
transactions. A crash between them legitimately resumes a smaller retained
window at the same checked tip. A failed compaction cannot advance the runner's
view or trigger a publish using the proposed anchor. Full history-derived state
identity, commitment to original bytes and retained undo ownership remain the
existing coherent transition's responsibility. Do not recompute old blocks from
re-encoded bytes.

## Focused combined tests required before wiring a live runner

| Cut or scenario | Required outcome |
| --- | --- |
| Bootstrap depth zero, invalid original, wrong context, unknown boundary | No serving session; no arbitrary trusted seed |
| Crash/cancel before and during reserved writer allocation | No second writer; late acquire joined and closed before settlement |
| Concurrent create/resume; close during allocation or force | Lifetime locks exclude overlap; writer settles before journal unlock |
| Every intent/store/commit force phase, including after replace and after directory force | No premature reply; exact old/new crash outcome, poison on uncertainty |
| Pending A→B with primary A | Fresh verification then forced abort, unchanged A |
| Pending A→B with primary B | Fresh verification then forced commit B |
| Committed B with primary A/unknown/missing | Fail closed; never fallback |
| Initial pending with no primary or temporary-only B | Abort to Dormant; no implicit bootstrap or promotion |
| Same token but altered full metadata; stale session/callback | Reject before permission or mutation |
| Controller commit succeeds, memory install/reply interrupted | Old reporting cache marked possibly stale; Resume selects B |
| Snapshot/prepare racing publish/rollback/advanceAnchor | Only old confirmed or new committed view, never proposal |
| Repeated Resume→rollback→publish→advanceAnchor | Fresh owner receipts, exact state IDs/claims and bounded retained suffix |
| Anchor move leaves ledger revision unchanged | New generation still forced; true anchor no-op changes nothing |
| Restart between compaction and next block | Compacted old tip resumes, next block can subsequently commit |
| Verify timeout, failed file force, unsupported atomic move, stale staged image | Poison/strict recovery; no mutation retry on active session |

Use deterministic phase hooks and existing reviewed bounded fixtures, not a live
cluster. Bound each Docker test process to 2 CPUs/2 GiB with private caches.
After these pass and the API is reviewed, integration can touch only the private
CoherentSequence helpers, ValidatorTransitions variant, and durable runner's
explicit rolling policy. Main/CLI changes require a subsequent integration patch.

## Authority wording to settle before implementation

Current v2 comments describe a controller outside the writer process. This
proposal intentionally has one process with separate journal/checkpoint
directories and private role boundaries. It supplies crash consistency and exact
local provenance, not process isolation, Byzantine resistance, protection from a
compromised process, or rollback protection when both domains are reverted.

Recommend keeping the checkpoint byte format and its crash-recovery authority
label unchanged only after explicit review confirms that these crash-only
semantics are the intended authority contract; update the misleading process
topology wording at integration. Independently version/pin the journal launch
policy as above. If the authority label is meant to promise process separation,
use a distinct authority label and reject cross-authority adoption instead of
silently changing that promise. The approved implementation retains the checkpoint authority label with these
crash-only semantics, updates the process-topology wording, and pins the launch
policy in versioned journal v2. It supplies no process isolation.
