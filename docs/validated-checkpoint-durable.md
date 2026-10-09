# Offline validated checkpoint storage and durable facade

This slice builds on the [pure recovery verifier](validated-checkpoint-recovery.md).
It adds package-private NIO storage and `CoherentSequence.durableCreate` /
`durableResume` resources, with a single exported `DurableRuntime` facade. It does
not add a live adapter, token-pair inspector or process-restart acceptance. The
format remains `restricted-validated-checkpoint-v1`; acquisition checkpoint bytes
and directories are not interchangeable with this store.

## Ownership and publication

The store owns a FileLock until close. The facade owns a fresh coordinator owner,
the private sequence state/undo Ref, session health/token, and one semaphore gate.
The ordinary in-memory runtime behind it never escapes. Snapshot returns state,
fence and exact token together under the gate. Preparation, publication, rollback
and close also use this gate. A candidate's owner/revision checks and the caller's
expected publication token must both pass before it can be published.

The existing pure coordinator transitions compute the whole next Cell privately;
the facade does not mutate via the ordinary runtime before writing. It encodes the
next snapshot, enters Publishing, records the pending old/next token pair, checks
the exact disk token, writes the entire staging file, forces it, closes it, atomically
replaces the published file and forces the directory. Only then does it install
the whole next in-memory Cell, prepare the acknowledgement and mark Active.
Generation advances once per publication; coordinator revision advances once per
applied/undone block. Current-tip rollback remains an unchanged-token no-op.

The failure/poison handler surrounds the complete publication region through
memory installation and acknowledgement preparation. It is not just a NIO-writer
guard. Failed publication cannot produce a new healthy snapshot, prepare, publish
or rollback until the resource is closed and reopened. Caller-stale tokens and
invalid transitions reject before publication and leave the facade usable; detected
disk-token divergence during publication poisons it. Generation exhaustion rejects
before mutation. Closing marks Closed and releases the FileLock under the SAME
gate, after any publication finishes; explicit close is idempotent.

File I/O and memory/ack preparation stay cancellation-masked. The pending-token
recorder runs before filesystem mutation in a separate fiber, with a positive
caller deadline capped at 30 seconds. Waiting on that fiber is cancelable and
bounded. On timeout/cancellation the facade poisons and requests child cancellation
without waiting for an internally masked recorder to cooperate, so that child
cannot hold the owner lock indefinitely. A noncooperative child MAY continue
external work after detachment: it receives tokens, not partial state capabilities,
and its output is only a pending-intent record, NEVER a publication acknowledgement.
Applications should still provide cooperative, idempotent recorders.

Pending cancellation during later masked publication may be delivered after the
complete disk/memory transition. That can lose the caller's response; cancellation
does not establish that the transition was aborted. The acknowledgement object is
prepared inside the poison region, but eventual delivery to another process is
outside this API's guarantee. The same rules apply to initial publication, which
records a pending token with no previous token.

## Create, strict reopen and files

Create uses a fresh cryptographically random 32-byte store identifier and publishes
generation zero. It rechecks absence of BOTH published and staging files AFTER
acquiring the lock. A delayed creator therefore cannot overwrite a store initialized
by a competing creator. The supplied parent directory must already exist. A lock-only
directory with no published/staging data can be explicitly created; abandoned staged
initial publication cannot be silently reinitialized.

Strict reopen requires the independently supplied context ID and exact token:
store ID, context ID, generation and payload digest. It reads bounded published
bytes, checks the token, executes the full pure recovery pipeline, and only then
deletes bounded staging residue and syncs the directory. Any acquire, inventory,
decode or full-replay failure releases the lock. Missing/corrupt published data
fails even when staging is a valid newer image. Staging is NEVER promoted.

Only empty `lock`, `validated.bin`, and optional `validated.tmp` are permitted.
Each checkpoint is at most 40 MiB, total checkpoint data at most 80 MiB. Parent
traversal, symlinks, unknown/nonregular entries, oversized files and nonempty lock
reject. Staging uses exclusive creation and a progressing short-write loop. Atomic
replacement has no non-atomic fallback. File force, checkpoint-directory force and
parent-directory force for new directory creation are distinct operations.

Locks are advisory, single-owner local protection, not protection against an
adversary replacing ancestors or restoring the entire filesystem. Synchronous force
or rename operations have no claimed hard wall-clock bound. File atomicity and the
tested sync call order do not establish hardware or power-loss durability.

Token-pair inspection is deliberately deferred. A publication with uncertain outcome
must not automatically resume under another token. Strict reopen using an old token
rejects a newer installed image; any separate decision to select another independently
retained exact token remains explicit. Restoring an older image is detectable only
when the independent token excludes it. Restoring both image and matching token is
not detectable here; checksums and generations confer no external rollback resistance.

## Offline test coverage

The new suite uses synthetic supplied anchors publicly and existing private original
blocks in the opt-in `COHERENT_SEQUENCE_EVIDENCE` lane; it creates no new cluster.
Filesystem and facade fault matrices exercise short writes, write stalls, pre/post
file force/close, pre/post replacement, ambiguous completed replacement errors,
directory-force boundaries, memory installation and acknowledgement preparation.
Every injected mutation failure checks poisoned snapshot/prepare/publish/rollback
behavior, followed by exact-token full replay of whichever complete image exists.

Deterministic barriers cover a delayed competing creator, concurrent close during
publication, pending cancellation at masked facade boundaries, and recorder failure,
timeout and cancellation before writes. The recorder deadline test includes an
internally masked child and explicitly releases it after verifying lock release.
Additional cases cover competing candidate CAS, no-op rollback, recovered undo and
foreign fences, unsupported atomic replacement, symlink/unrelated/oversize inventory,
bad tokens, recovery failure lock release, stale staging, and all operations after close.

This is offline filesystem/replay evidence. No graceful process-restart, post-ack
process-death, during-publication process-death, or power-loss claim is made.


## Verification receipt

The isolated offline JDK21 run used network none, 2 CPUs/2 GiB, a task-owned cache
and read-only retained sequence/single-transaction evidence. Formatting checks and
303 application tests passed, including all 15 new durable test cases (several are
phase matrices). Independent source/test/receipt review closed without blockers.
Logs are retained outside Git in
`/home/<user>/cardano-validated-durable-evidence-20261009/durable-tests4.log`.
Earlier compile/test logs are preserved there: exception-handler scoping and an
OpenOption varargs runtime type mismatch were corrected before the successful run.
No new cluster, live adapter or process-restart run was performed.
