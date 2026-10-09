# Durable restricted validated-state checkpoint: design for review

Date: 2026-10-09. Source inspected read-only at
`ba443fbf1f71b7a42881f97a3771f95a1a056185` in
`<repo>`. This document proposes implementation; it
does not report a new implementation, build or live acceptance.

## Scope and representation

Persist the supplied anchor context and retained original blocks, then reconstruct
the complete restricted validated tuple and undo stack by checked replay. Use a
different magic, profile, directory and store interface from acquisition checkpoints.
An acquisition cursor cannot construct a validated-state recovery capability.
The supported window remains same-epoch, at most eight blocks, zero through sixteen
supported transactions per block. Full ledger and consensus validation remain false.
The supplied reference anchor remains unauthenticated and non-atomic; recovery does
not upgrade its provenance. An empty retained window has no scoped applied tip.

One versioned binary file contains, in fixed canonical order:

- Format/profile, random store-instance identifier, network/epoch/anchor and capacity.
- Strict context manifest and exact seven pre-anchor source byte strings; context ID.
- Zero through eight original header-envelope/block pairs, in chain order.
- Publication generation G, coordinator revision R, expected final tuple content ID.
- A checksum over all preceding fields, with explicit lengths and no trailing bytes.

Use existing source bounds (seven sources at most 4 MiB each, manifest at most
8 KiB), header envelopes at most 65,535 bytes, and blocks at most 1 MiB. A 40 MiB
file ceiling leaves ample framing room. Permit only an empty owner lock, published
file and staging file, at most 80 MiB data total. Validate bounds before allocation,
fixed key sets and canonical numeric encodings. Enforce a bounded recovery deadline
as well as input bounds. The checksum detects accidental corruption; it is not a
signature, freshness guarantee or authentication of supplied context.

Do not serialize decoded UTxO maps, reconstructed transaction memos, eligibility
objects, coordinator receipts, private ledger heads, candidates, fences or owners.
The expected final content ID is a replay assertion, not a substitute for replay.
This anchor-snapshot-plus-originals format avoids duplicate final snapshots and
keeps all derived state reproducible. Serializing receipts would couple the format
to private constructors, revision-sensitive heads and undo internals, and would
require separately validating every cross-object binding. It is not the first step.

## Source evidence and revision proof

All references below are local paths and line numbers at the pinned commit; use
`git show ba443fbf1f71b7a42881f97a3771f95a1a056185:<path>` if main changes.

| Source | Relevant fact |
| --- | --- |
| `app/src/main/scala/lab/CoherentSequence.scala:36` | Tuple state; line 44 defines coordinator revision as `ledger.revision`, not another counter. Content ID binds context/acquisition/certificate/nonce/eligibility/ledger IDs. |
| `app/src/main/scala/lab/CoherentSequence.scala:61` | Private owner-bearing fences and candidates. |
| `app/src/main/scala/lab/CoherentSequence.scala:134` | Normal seed checks supplied anchor bindings and ledger revision zero. |
| `app/src/main/scala/lab/CoherentSequence.scala:234` | Publication checks owner/current content/revision and commits one ledger block into the tuple and undo stack. |
| `app/src/main/scala/lab/CoherentSequence.scala:263` | Whole-tuple restore, including certificate, nonce, eligibility and ledger undo. |
| `app/src/main/scala/lab/CoherentSequence.scala:291` | Retained-prefix rollback and revision-capacity preflight; current-tip rollback is a no-op. |
| `app/src/main/scala/lab/CoherentSequence.scala:322` | Current in-memory runtime and atomic Ref operations; line 345 creates a fresh owner. |
| `ledger/src/main/scala/lab/ledger/ClusterTransition.scala:239` | State construction; content digest excludes revision and private head. |
| `ledger/src/main/scala/lab/ledger/ClusterTransition.scala:265` | Supplied checkpoint starts at revision zero with no private head. |
| `ledger/src/main/scala/lab/ledger/ClusterTransition.scala:503` | Bounded block fold; per-transaction intermediate revisions reset to the block's starting revision at line 544. |
| `ledger/src/main/scala/lab/ledger/ClusterTransition.scala:555` | Both tentative and final block state use starting revision + 1 (lines 561 and 583); transition hash binds revision/head. |
| `ledger/src/main/scala/lab/ledger/ClusterTransition.scala:589` | Block commit checks predecessor content/revision/head and creates its private undo. |
| `ledger/src/main/scala/lab/ledger/ClusterTransition.scala:666` | Undo checks current revision/head and returns restored content at current revision + 1. |
| `app/src/main/scala/lab/SequenceInput.scala:91` | Strict context loading/binding; seven originals, independently hash-bound manifest. |
| `app/src/main/scala/lab/SequenceInput.scala` (`block`) | Re-extracts original body/witness spans and supported block bounds; construction alone is not validation. |
| `app/src/main/scala/lab/NioAcquisitionCheckpointStore.scala` (`publish`, `initialize`) | Existing force/atomic-replace/directory-force pattern, poisoned publication and no staging promotion; reuse the protocol, not acquisition validity. |

Let n be the retained block count, R the saved coordinator revision, and M the
ledger uint64 revision maximum. A legitimate history from the supplied anchor has
A successful block applications and U individual block undos. Then n = A - U
and R = A + U. Consequently R >= n and R - n = 2U is nonnegative and even.
Current-tip no-ops change neither quantity; failed operations publish neither.
Validate `n <= R <= M` and even `R - n` for this versioned profile. These checks
are consistency checks, not proof of historical events or protection against a
forged, rechecksummed file without an independent token.

Recovery privately seeds the same supplied anchor content at r0 = R - n, with
no private ledger head. This is a new recovery lineage, not reconstruction of old
process capabilities. For each original block i, checked `prepareBlock` and
`commitBlock` produce exactly r_i = r_(i-1) + 1. This holds for:

- Empty blocks: the transaction fold leaves content unchanged; final block state
  still advances slot and revision once.
- One or multiple transactions: each private intermediate is normalized to the
  block's starting revision before transaction preparation. Intermediate transaction
  candidates do not escape. Final block state advances once, not transaction-count
  times, including a sixteen-transaction block.

The coordinator's revision is the ledger revision by definition. Therefore after
n replayed blocks both equal `(R - n) + n = R`; there is no second coordinator
counter to offset. Every intermediate revision is <= R <= M. For n > 0 each
block starts below M; n = 0 permits a recovered anchor at R, including exhaustion.

Content reconstruction is independent of that offset: ledger content IDs omit
revision/head, and the other tuple transitions derive from pinned context and
originals. The verifier nevertheless requires the final tuple ID and revision to
match. Revision-sensitive ledger transition heads ARE regenerated at the new
offset. Each new undo binds the new before-state/head and new after-state, so it
is valid within this lineage. It need not equal the old process's transition ID.

For subsequent rollback by k retained blocks, each fresh undo restores predecessor
content/head while incrementing the current revision. The final revision is R+k,
and the coordinator preflights that bound. Undo does not reset to the receipt's
historical before revision. Thus rebuilt prefixes remain rollback-capable with
monotonic revisions. Reapplication then adds one revision per block. All old
process candidates/fences fail the fresh-owner check even if content/revision match.

Examples: empty + two-transaction block gives n=2,R=2; undo one gives n=1,R=3.
Recovery seeds at 2, replays one block to 3, then undo reaches 4. Undoing all from
n=2,R=2 gives n=0,R=4; recovery seeds at 4 and retains no undo. This does not
claim an applied tip at that supplied anchor. G is separate: one multi-block
rollback publishes once while R increases by its undo count.

## Concrete capability boundary (proposed, not existing APIs)

Expose resource constructors conceptually equivalent to:

`create(root, IndependentlyPinnedContext, capacity)` and
`resume(root, IndependentlyPinnedContext, ExactPublicationToken)`.

Both return an owned durable runtime only after validation. No `resumeUnchecked`,
optional expected token, public revision setter or `State.fromBytes` belongs in v1.

The decoder returns a bounded **untrusted envelope**, never a `State`, candidate,
fence, undo or runtime. A verifier rebinds the exact context sources against caller
pins, enforces format/numeric constraints, seeds privately, parses each original,
and runs the normal complete sequence checks. It alone creates a private
`RecoveredCell` capability after final ID/revision agreement. The durable factory
consumes that capability while owning the directory lock. No caller can construct,
copy or retain a partially verified cell; replay produces no externally visible
acknowledgements and does not overwrite the checkpoint it is verifying.

Normal `create`/seed behavior stays revision zero. A necessary narrow ledger helper
would rebase ONLY a freshly checked supplied anchor: require revision zero and no
private head, preserve checkpoint/environment/content, check uint64 offset, and
return another supplied anchor rather than validation evidence. Keep it internal
to the recovery implementation (with the smallest package visibility needed across
modules); never accept an arbitrary final tuple or deserialize private receipts.
The coordinator recovery factory is the sole planned consumer. It must run normal
seed validation before applying the offset and must not return this intermediate.

Publication images likewise come only from a successful private complete transition
or successful recovery verification. The NIO byte store cannot manufacture validated
state authority. Fresh owner tokens are allocated on each successful open; disk
store instance IDs identify storage, not runtime ownership capabilities.

## Expected-token and uncertain-publication policy

A token is `(storeInstanceId, contextId, publicationGeneration, fileDigest)` and
binds the entire envelope, including R and the expected final tuple ID. Creation
requires a new empty directory; it returns the initial token after publication.
Strict resume requires an exact independently retained token and context pin.
Every mutation compare-and-swaps the current token and increments G once; no-op
rollback does not write or increment G. G must have an explicit exhaustion bound.

The application/controller is responsible for retaining acknowledgements outside
the checkpoint's rollback domain. For restart tests it records the token in a
separate controller receipt before killing the child. This is not a claim of an
independently durable, tamper-proof high-water service. Restoring an older valid
checkpoint is rejected against a newer external token. Restoring BOTH checkpoint
and token to matching old values cannot be detected by this design.

If publication is uncertain, strict resume with the last acknowledged token may
reject a newer installed file; that is intentional. Provide a separate inspection
operation accepting an independently recorded pending pair `(oldToken, nextToken)`.
Require same store/context and `next.G = old.G + 1`, fully replay the published file,
and report `RetainedOld` or `InstalledNext` only for an exact match. Return a report,
not a usable runtime. Caller explicitly selects the resulting exact token for
normal resume. Neither match means failure. Never promote staging or choose an
arbitrary valid file. If the next token was not independently retained before the
attempt, this bounded reconciliation path is unavailable; stop for separate review.
A controller hook can retain the precomputed pending pair before starting disk I/O;
failure to record it must abort before publication. It is not silently inferred
from the file being recovered.

## Publication and recovery protocol

Use one gate for mutations and publication ownership. Check owner/fence/revision,
compute the next whole tuple and undo stack privately, encode its image, verify the
current disk token, write all staging bytes, force the staging file, atomically
replace the published file, force the parent checkpoint directory, install the
entire in-memory cell, and only then return the acknowledgement/token. Mask
cancellation across disk publication and cell installation. Do not first publish
via today's `Ref.modify` and then write to disk; do not perform blocking I/O inside
the Ref update callback. Use the pure transition computation with a gated durable
commit path. Serialize snapshot admission with that gate so poison/failure cannot
race a newly issued apparently healthy snapshot.

Any publication error poisons the owner/runtime until reopen; do not report normal
success, continue from old memory, or treat it as a peer retry. After replacement,
an exception may leave new disk with old memory. Reopen/reconcile handles that
uncertainty. Already-returned immutable snapshots cannot be revoked, but no new
operations succeed on the poisoned runtime.

Recovery locks first, checks the directory inventory/no-links policy and token,
fully replays privately, then exposes state. Missing/corrupt published data fails
closed even with valid staging. Bounded staging residue is deleted only after the
published image validates; directory deletion changes are synced. No history
eviction, implicit reanchor, downloaded trusted tip, or fallback to another file.

Atomic replacement concerns whole-file visibility. File force and directory force
are distinct; directory creation also requires the parent directory sync. An
acknowledgement follows these operations and memory installation. Observed graceful
or post-ack SIGKILL acceptance does not establish interruption-during-publication,
hardware persistence or power-loss safety. Unsupported atomic moves/sync fail
explicitly rather than degrading to a weaker protocol.

## Implementation order and acceptance

1. Pure bounded format, untrusted envelope parser and replay verifier.
2. Narrow anchor-revision recovery bridge; tests of exact content/revision and every
   rebuilt retained-prefix rollback. Preserve normal create semantics.
3. Dedicated NIO store and durable coordinator gate; keep in-memory API available.
4. Adapter-only graceful and post-ack SIGKILL acceptance after offline review and
   a separately authorized resource slot. Do not combine this with during-write death.

Required tests: corruption/truncation/oversize/extra fields; rechecksummed invalid
originals; wrong context/profile/store/token; R<n, odd R-n, capacity/overflow;
empty, single and sixteen-transaction block revision arithmetic; late transaction
rejection; save after rollback/reapply and n=0,R>0; recover all retained prefixes;
fresh-owner rejection of old candidates/fences; separate G versus R accounting;
stale and conflicting writers; bounded lock/inventory/symlink handling; cancellation
and injected failures before write, after write/force, before/after install, after
directory force and before acknowledgement; poison prevents further reads/mutations;
valid staging never rescues missing/corrupt published data; uncertain old/new exact
token reconciliation; and old-valid restoration with versus without independent
token retention. No arbitrary state constructor bypass should be added for tests.

Use existing supported synthetic ledger and retained real-original lanes with their
scope labels. Recovery tests must compare whole tuple contents, current revision,
undo behavior and fresh ownership, not merely content digests. Genuine alternate
valid Praos branch acceptance remains a separate evidence gap unless suitable
originals are available. Retained keys/logs/state remain outside Git.
