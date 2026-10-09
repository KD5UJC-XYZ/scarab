# Pure crash-only controller reducer

`app/src/main/scala/lab/ControllerReducer.scala` implements the protocol described in
the separate controller-receipt design packet (commit
`bbcaf896bab429be534a60729e531ebb4500d628`,
`docs/controller-receipt-design.md`) as a pure reducer. It executes no journal I/O,
supervisor action, checkpoint verification, or runtime recovery. It does not wire
the version-2 checkpoint capability into the node. This is acquisition/checkpoint
authority bookkeeping, not a new consensus or ledger validation boundary.

## State and effects

The bounded journal retains one selection, one lease, at most one pending
publication, and one terminal publication result. IDs and digests are exactly
64 lowercase hexadecimal characters; counters are nonnegative signed longs.
There is no growing receipt or retired-session history. Exhausted revision or
session counters reject transitions rather than wrapping. Exact publication
successors increment generation by one, or initialize generation zero.

`open` validates the typed journal and takes a fresh controller incarnation ID.
An adapter must supply a strictly decoded durable journal and a unique incarnation
on every controller startup. Reopening suppresses permissions from an old lease;
startup must retire and settle prior ownership before reserving a fresh epoch.
The reducer itself supplies neither a byte codec nor an authenticated storage
format. Those remain adapter work.

`step` returns a proposed machine state, a typed reply and required effects.
While a `Force` is pending, the machine's `durable` member remains the predecessor.
A force ticket binds the controller incarnation, revision, operation identity and
complete proposed journal. Only its matching `Forced` completion installs the
proposal and releases subsequent effects or `Prepared`, `Committed`, `Selected`
or `Serving` replies. An uncertain force halts the machine for strict reload.
The adapter must execute effects in this order, persist the returned machine in
its serialized event loop, and never infer permission from a proposed journal.

Callbacks are fenced by epoch/session and the expected phase/work, with exact
child, launch or claim identity where applicable. Stale callbacks are rejected.
Matched external failure or an authoritative unexpected disk image halts.
Exact current-phase duplicate publications can replay their previous reply;
an older operation cannot overwrite newer journal state. Pending verification
does not reissue a write permission. Callers retry a pending force through the
adapter's force operation, not by inventing a completion.

## Launch and ownership evidence

`LaunchExact` is emitted only after the reserved launch identity is forced.
Binding its child is also forced before acquiring the store lock. Lock evidence
enters Probe, whose inspection and verification must finish before Serving can
be forced. Probe initialization can publish generation zero only after a checked
missing-image result. It cannot serve an unselected migration destination.

`NoMatchingChild` is only an observation. It never settles a launch and never
permits another writer. `Retire` forces retirement before its `SettleOrStop` effect. A
`NoMatchingChild` observation may also request settlement/stop while the lease is
live, but cannot release ownership or grant another launch. A late child
binding during retirement emits only another stop/settlement effect. To finish
retirement, `OwnershipEnded` requires trusted evidence that the exact earlier
launch issuer/request can no longer create a child. If a child was bound, its
exact exit and lock release are additionally required. A process-list miss,
timeout, cancellation request, or controller restart is not this proof.

The evidence IDs are typed references to adapter attestations, not cryptographic
proofs checked by this reducer. The future supervisor must bind them to the
requested launch, make launch execution idempotent/exclusive, and account for
in-flight create requests. Fabricating a settlement token violates the interface.
The reducer deliberately offers no independent second-launch retry while old
ownership is uncertain. Session/launch IDs must be fresh; the bounded journal
checks the retained predecessor and epoch fences callbacks from older sessions.

## Recovery and migration

Retirement preserves a pending predecessor/successor pair and migration metadata.
A fresh Probe accepts only the exact recorded predecessor or successor, then
requests verification. Verified successor commits, verified predecessor aborts,
and checked absence aborts only an initial generation-zero publication. Recovery
must be repeated after every restart; reopening an image does not restore serving
permission. Unknown images stop recovery without selecting a fallback.

Migration requires a quiescent acknowledged source. The target uses a separate
store identity in the same context, initializes and verifies its own claim, then
requires a forced selection before activation. A verified destination alone is
not a serving permission. No source fallback or runtime adoption is implemented.

## Validation and limitations

The offline Docker run of `app/testOnly lab.ControllerReducerSuite` passed all
14 tests with 2 CPUs, a 2 GiB memory limit, disabled networking and a private
worktree cache. `scalafmtAll` completed; independent source review found no
remaining code blocker.

`ControllerReducerSuite` exercises permission ordering, exact duplicates, stale
callbacks, delayed forces, cross-incarnation/proposal force collisions, repeated
recovery, missing initial images, migration selection, exhaustion, malformed
schemas, uncertain forces, external failures and late child creation.

The tests use deterministic pure events. They do not establish filesystem force
semantics, process termination, OS-lock behavior, cryptographic checkpoint
verification, or live cluster behavior. Future adapters must establish those
properties and retain the crash-only fault model; adversarial corruption or
rollback of the controller journal is outside this slice. There are no NIO,
supervisor, launcher, `Main.scala`, or version-2 runtime integration changes.
