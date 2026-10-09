# Combined local v2 durable backend

`CombinedLocalV2` now connects one controller journal owner, one exclusively
locked checkpoint writer, and one private coherent runtime. Its package-private
`create`/`resume` resources return only a gated `Session`: `snapshot`, `prepare`,
`publish`, `rollbackTo`, `advanceAnchor` and diagnostic `lastConfirmed`. Views and
prepared candidates are opaque and tied to that session. No raw runtime,
controller, accepted authority, disk handle or proposed cell escapes.

This packet does **not** wire `ValidatorTransitions`, either runner, the ordinary
node or the CLI. Their existing v1 confirmation and telemetry paths remain
unchanged. The combined session is the tested backend for the next narrow
integration patch, which must introduce distinct v2 full-claim confirmations.
It must not squeeze a v2 token into the existing v1 token path.

## Fixed authority and launch policy

Checkpoint bytes remain `restricted-local-derived-checkpoint-v2` with authority
`reviewed-local-writer-crash-recovery-v1`. Trust is in separately persisted exact
claims and private controller/writer roles within one trusted process. There is
no process isolation, protection from compromised code, proof of discarded
history, or protection against rollback of both persistence domains.

The journal is now `controller-journal-v2`; its immutable binding includes
`in-process-resource-v1`. Resume rejects old unmarked journals and other launch
policies before any adoption, write or retagging. This intentional journal
compatibility break does not change the checkpoint v2 codec. Store/context/path,
profile, format and authority remain independently pinned and exact.

The launch discriminator is a protocol assertion, not authentication of an
arbitrary file. Only this structured lifetime contract may justify lock-based
settlement; malformed, manually forged or externally supervised journals are not
silently adopted.

## Initialization and resume

Create accepts an explicit context, one to capacity original blocks (capacity
1..8), and a checked compaction boundary. It privately validates all originals,
explicitly compacts a nonempty prefix, and publishes generation zero. The factory
performs no live acquisition, accepts no caller-supplied Runtime, and exposes no
un-compacted or intermediate state. There is no automatic v1 migration or initial
retry loop. Empty originals and depth-zero compaction are rejected.

The controller is acquired first. Create forces its reserved launch before
writer allocation. Acquisition remains masked until its resource/finalizer is
registered, so cancellation waits for late acquisition and closes it before
controller ownership can be released. The logical child ID denotes that local
handle, never an OS process or an external-supervisor request.

Resume first retires any old lease, then takes the existing store lock in
quarantine. Only under both locks and the pinned structured-resource policy may
it settle the old handle, reserve a new lease and adopt that continuously held
store lock. Missing initial checkpoint directory or lock is initialization-required:
Resume creates neither, preserves the unresolved old lease, and grants no writer.

Primary inspection allows only the journal's exact complete acknowledged claim
or pending predecessor/successor. The frozen registry and current lease authorize
one fresh accepted-authority recovery while the store lock is held. Ordinary
suffix replay regenerates fresh owner-bound receipts. Unknown or missing committed
primary bytes fail closed; staging is never promoted. Initial pending absence can
resolve to Dormant but still returns initialization-required, never bootstraps.
Activate is forced before any recovered session is returned.

## Durable transitions

The private `CoherentSequence.LocalPlan` binds its original owner and exact
predecessor cell. It can export the proposed cell without modifying live memory;
installation rejects foreign or stale plans. All combined mutations hold the
same gate as snapshots, preparation and close:

1. Check the current opaque view, plan the transition, and encode its full claim.
2. Force the exact controller intent and require Prepared.
3. Install the checkpoint with exact predecessor/full-claim CAS, file force,
   atomic replacement and directory force.
4. Read the actual primary under its lock, match the complete requested claim,
   and verify bounded accepted-authority recovery.
5. Force the controller commit and require its exact Committed reply.
6. Install the planned memory cell and update the current/reporting view.

Only then can the caller receive success. A post-commit memory failure poisons
the session; `lastConfirmed` can be older than disk, while Resume selects the
committed successor. All uncertain publication failures refuse further mutation.
The unchanged checkpoint is not a fallback merely because its caller missed a
reply. Pure rejected transitions leave the current view unchanged.

Compaction uses this entire protocol and advances checkpoint generation even
when ledger revision is unchanged. True no-ops retain the same view and generation.
Rollback remains limited to the retained checked window, not a finality claim.
The future runner adapter must fetch/parse, durably make room, then prepare the
next block; compaction and that block are separate durable transactions.

Mutation persistence is masked until a known outcome. Cancellation may suppress
a successful reply after commit, but cannot expose a proposal or permit a second
writer. Close is idempotent, shares the mutation gate, retires when possible,
closes the writer before releasing the journal, and attests ownership end only
after physical release. A poisoned journal may retain an unresolved lease; it
does not prevent physically closing the writer or justify a fabricated receipt.
Blocked filesystem calls may delay cancellation/close rather than losing ownership.

## Validation and remaining integration

Focused tests cover version/policy rejection without retagging, missing initial
locks, explicit bootstrap checks, publish/rollback/compaction across repeated
resumes, every combined publication cut, uncertain checkpoint and controller
forces, post-commit memory failure, late acquisition cancellation, snapshot gating,
writer-before-journal close, stale/foreign views and committed successor refusal
of predecessor/missing primary bytes. Separate byte-store tests cover all write
cuts, exact full-claim CAS, FIFO/symlink rejection, short/stalled writes, atomic
replace failure, parent force retry and finalizer faults.

Validation passed all 56 focused tests: 11 combined lifecycle, 10 byte-store,
12 journal adapter, 14 reducer and 9 v2 codec/recovery tests. Docker networking was
disabled, CPU limited to 2, memory to 2 GiB, and build caches private to the
worktree. Formatting and diff checks passed; independent lifecycle review found
no blocking issue.

A separate acceptance coverage packet adds four tests (24 tests passed with the
11 combined lifecycle and 9 codec/recovery tests):

- Directly encoded and forced abrupt-stop fixtures retain unresolved live leases
  and exact pending operations before the first Resume. Pending A-to-B selects
  exactly primary A or B. Initial pending absence and staging-only bytes resolve
  to Dormant and initialization-required without promoting staging or bootstrapping.
- A verification timeout after checkpoint replacement holds recovery cancellation
  cleanup open. The mutation cannot return or release journal ownership until
  cleanup settles; afterward the session is poisoned, its diagnostic claim/state
  remains unchanged, and strict fresh Resume selects the exact disk successor.
- Six interrupted anchor-publication phases check independently encoded full
  claims and state identities, then Resume and publish the next original block
  against an independent checked runtime.

These abrupt-stop fixtures intentionally open no backend or controller resource
before Resume, so graceful finalizers cannot rewrite their starting states. They
model persisted crash cuts; they are not a process-kill or hardware power-loss
experiment. Recovery instrumentation is a private labels-only hook inside the
existing bounded timeout; production entry points supply a no-op observer.
The acceptance run used the same offline Docker limits and private cache policy
as the baseline. Independent read-only review found no blocking issue.

Tests use existing signed private capture evidence mounted read-only. No live
node, network fetch, process spawning or migration execution runs. The only
remaining product integration is the runner/backend confirmation adapter and
later explicit ordinary-node configuration; this packet changes no telemetry or
CLI behavior. Test hooks are trusted instrumentation, not an external
supervisor interface.
