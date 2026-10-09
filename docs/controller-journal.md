# Single-store local controller journal

`LocalControllerJournal` persists the approved `ControllerReducer` for one local
controller and one configured checkpoint store. It is deliberately unwired:
there is no node launcher, supervisor, checkpoint installer, runtime recovery
entry point, or live cluster action here. It supports eventual durable node
integration by making reducer force acknowledgements correspond to actual local
filesystem persistence. It does not turn acquired bytes into ledger validation.

This packet depends on the approved reducer and `LocalDerivedCheckpoint` v2
packets. Their sources are unchanged by the adapter increment.

## Interface and immutable configuration

Construct a `ControllerJournalCodec.Binding` with independent absolute normalized
journal-directory and checkpoint-directory paths, store ID, context ID, profile,
format and authority. Paths must not overlap. Symlink components are rejected.
The adapter compares this supplied binding with the complete persisted binding
on every load. The binding cannot be changed by a reducer message. Only the
reviewed v2 format, profile and crash-recovery authority are accepted.

`LocalControllerJournal.resource[F]` uses `Async` and owns a lifetime OS file lock.
`Create` explicitly writes a new Dormant journal; it rejects a published or staged
image. `Resume` requires the existing lock and primary journal. Neither mode
launches a child or initializes a checkpoint. A fresh controller-incarnation ID
is required on each resource acquisition. The caller supplies this ID; this
adapter does not claim to detect repeated IDs across arbitrary historic starts.

The controller offers `submit`, `submitBytes`, and a diagnostic `snapshot`.
Results contain the durable journal, reducer reply, and external effects that a
future trusted adapter must execute. A returned snapshot is data, not a runtime
or authority capability. This slice provides no `ControllerAuthority` instance.

## Bounded journal and message codecs

`ControllerJournalCodec` uses explicit versioned binary tags, fixed 32-byte IDs,
bounded length-prefixed UTF-8, canonical decimal large integers, fixed-width
nonnegative counters, and exact input exhaustion. A SHA-256 checksum detects
accidental damage; it is not authentication. Each encoded journal or message is
limited to 32 KiB, including checksum. Paths are limited to 4096 UTF-8 bytes;
policy strings to 128 bytes. There is no recursive or unbounded container grammar.

The journal retains at most six complete `LocalDerivedCheckpoint.Claim` values,
covering exactly the distinct reduced claims referenced by its acknowledged
selection, pending operation, last operation and verified lease. Full claims
include token store/context/session/generation/digest, format/profile/authority,
anchor ID, final ID, compacted depth and revision. Unreferenced claims are removed
after each transition; missing, duplicate or conflicting claims are rejected.
Even no-write duplicate publication requests compare all retained metadata.

A `Begin` message carries exactly one full successor claim matching its reduced
operation. Other messages carry no additional claim registry. Messages are
checked before reducer execution. Migration commands and Migrating journal
images are unsupported and rejected. Caller-supplied `Forced` and
`ForceUncertain` are unrepresentable in the accepted message format and rejected
by the typed `submit` path too.

Decoded ownership, lock, inspection and verification evidence is still a trusted
adapter assertion. This module provides no remote endpoint or authentication.
In particular, a caller cannot interpret a successful parse, a matching checksum
or an arbitrary settlement ID as proof that an old launch can no longer create a
writer. The reducer's exact launch-settlement obligations remain in force.

## Persistence and cancellation

One semaphore covers processing, publication and in-memory state update. For a
reducer `Force`, the adapter first validates the complete proposed image, compares
the exact authoritative bytes with its predecessor, then:

1. Opens a new staging file and writes the bounded image, checking progress.
2. Forces the file and closes it.
3. Atomically replaces the primary image; there is no non-atomic fallback.
4. Forces the journal directory.
5. Internally delivers the matching `Forced` completion and updates memory.

Only then can the caller receive `Prepared`, `Committed`, `Serving` or a launch
effect. Explicit Create also forces the parent directory even if an earlier
attempt already created the journal directory. This repairs the parent-force
retry edge without an automatic initialization retry loop.

Any installation failure conservatively poisons the controller. It issues no
success reply and refuses further commands. The caller must close and strictly
reopen. Resume accepts only the primary journal, never a newer staging file.
After strict primary decoding it may discard an orphan staging file and force
that removal. Missing or invalid primary authority is an error.

Persistence and memory installation are uncancelable once the serialized command
begins. Waiting for the gate remains cancelable. Closing acquires the same gate,
waits for an in-flight force, and then releases ownership. Cancellation can lose
a reply after a successful write, so callers must use the retained journal and
phase-specific idempotency; cancellation never implies that a write failed.

## Validation and scope

`ControllerJournalSuite` tests full-claim retention and duplicate conflicts,
strict codecs including every truncated payload prefix, binding mismatches,
exclusive ownership, all publication phase faults, short and stalled writes,
unsupported atomic replacement, exact predecessor checks, stale concurrent
callbacks, cancellation/close ordering and staging non-promotion. The focused
Docker run also executes `ControllerReducerSuite`.

Validation passed all 26 focused tests (12 adapter and 14 reducer) in an offline
Docker container limited to 2 CPUs and 2 GiB, using a private worktree cache.
`scalafmtAll` and the staged diff check passed. Independent source review
verified the complete-claim duplicate and parent-directory-force fixes and
reported no remaining blocker.

The test hook is trusted test instrumentation; normal use takes `NoFaults`.
Tests exercise local Linux NIO behavior and injected failures, not physical power
loss guarantees for every filesystem. The supported threat model is trusted
local crash recovery: hostile writers, privileged filesystem tampering, rollback
of both persistence domains and arbitrary forged evidence are outside scope.
No new dependencies, security settings, main/runtime wiring, migration execution,
supervisor orchestration, or live-run behavior are introduced.

The adapter follow-up additionally tests FIFO lock, primary and staging paths in
both Create and Resume, and an explicit Create retry after directory creation
succeeded but parent force failed. A trusted `Faults.forceParent` hook exercises
that boundary; normal behavior still performs the real parent-directory force.
