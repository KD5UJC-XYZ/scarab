# Bounded durable restricted replay research store

This slice adds a separate `ledger-runtime` module: a tagless-final
`ReplayStore[F]` and a Cats Effect `Resource`-owned JVM NIO interpreter. The pure
`RestrictedReplay` ledger projection and existing acquisition/segment stores are
unchanged. The durable store implements one narrow fixed-context Conway PV9
UTxO/fee projection, not a complete Cardano ledger, block validator, chain-sync
consumer, consensus database, authenticated state root or selected-chain ledger.
All existing profile omissions remain. The historical tick is recorded, not run.

## Ownership and API

`NioReplayStore.create(root, Checkpoint(parameters, originalUtxo, attributionDigest))`
requires a missing or empty dedicated directory. `open(root)` requires a complete
selected head. Both hold one operating-system file lock until Resource release.
Two owners in the same process or cooperating separate processes cannot write
concurrently. Each owner serializes snapshots and publication with one semaphore;
its returned state, fees, head, revision and undo tip are one immutable snapshot.
A separate one-permit work gate serializes whole commit/rollback operations, so at
most one provisional pure operation exists per owner; snapshots remain available
during pure preparation. Lock order is always work gate then publication gate.
Waiting calls can cancel without consuming a revision or poisoning the owner.
Calls through a released owner fail, including reads.

`commit(expectedVersion, originalTransactions)` performs pure checked batch
application before acquiring the writer for publication. Under the writer it
checks the exact owner session, durable head and revision again. Any failed batch
returns no partial state or writes. Empty batches preserve the exact snapshot and
revision. A successful nonempty batch creates one local research commit.

`rollback(expectedVersion, transitionId)` requires the checked top transition of
the current branch. Pure undo checks original spent/created output bytes and state
identity. Undo restores UTxO content and fees but advances revision. Apply, undo,
reapply therefore produces revisions 1, 2, 3 and a different transition identity.
Old branch, revision, head and session tokens cannot commit. Reopening creates a
new session, so even an otherwise unchanged token from the old owner is stale.
Session tokens are ephemeral capability fences, not persisted identifiers or
cryptographic user authentication.

A canceled or interrupted caller cannot infer whether its commit took effect.
Reading/reopening establishes the selected result. Reusing the old expected
version fails if publication happened. Reapplying an already-spent original
transaction against the current snapshot fails input resolution, so uncertainty
cannot apply the same fee twice. Intentional rollback followed by explicit
reapplication is a new revision with one current fee charge.

## Persisted format and checked recovery

The directory contains only:

- `lock`, a zero-byte advisory owner lock
- `head`, one strictly framed pointer containing seed hash, tip hash, revision and
  history length
- `objects/<sha256>.bin`, immutable seed or operation objects
- `staging/object.tmp` and `staging/head.tmp`, at most two abandoned/transient files

The format uses fixed ASCII domain headers, big-endian fixed-width integers and
bounded length-prefixed bytes. Unknown tags, trailing bytes, oversized fields,
noncanonical optional tags and inconsistent ancestry fail closed. Object names
are SHA-256 content identities. The seed binds the pinned pure profile and original
parameter/UTxO bytes plus attribution digest. Attribution identifies caller-supplied
provenance and does not authenticate it.

An apply operation stores the exact original transaction envelopes. An undo stores
the specific prior top-transition identity. Every operation also stores a redundant
revision, semantic state identity, fee accumulator, exact output-map bytes and
current undo-tip identity. These are assertions to verify, never acceptance flags.
Recovery follows the selected bounded ancestry to its seed, verifies hashes and
strict framing, reconstructs the initial checked state, reruns every accepted
transaction through `RestrictedReplay.applyBatch`, and reruns each undo through
`RestrictedReplay.undo`. It regenerates original-byte deltas and transition IDs;
it does not deserialize a caller-created success token or trusted undo structure.
Every redundant summary and the selected head revision must exactly match replay.

A missing/corrupt selected head, seed, operation, parent link, transaction or
summary fails. Recovery never searches for a convenient older head, promotes an
orphan, accepts a success flag, truncates a bad history or silently repairs it.
An interrupted initialization before head replacement is uninitialized and fails
to open; the caller must explicitly choose a fresh empty directory. After replacement,
complete genesis can reopen even if initialization was not acknowledged.

Unselected recognized immutable objects and staging files are abandoned work, not
committed history. They count toward storage quotas and are never selected by
recovery. The next authorized commit may replace the two staging files; immutable
objects are never rewritten. There is no garbage collection, automatic repair,
compaction, checkpoint import, journal pruning or unlimited service mode.

## Publication and cancellation

One commit writes an object to staging (including a partial-write boundary), forces
its file, atomically installs it, then forces the objects and staging directories.
It writes and forces the new head staging file, atomically replaces `head`, then
forces the root and staging directories. Only after those steps is the in-memory
snapshot published. The head replacement is the atomic-selection linearization
point; successful return additionally requires the subsequent forces and coherent
in-memory publication.

Waiting for either gate and the effect boundaries around bounded pure transaction
checks/undo are cancelable before publication. Synchronous pure evaluation itself
finishes its bounded call before observing cancellation. The bounded mutation section is cancellation-masked:
a cancellation request during writes, force, install, replacement or publication
waits for the complete operation. A terminal masked call may return success; a
subsequent cancelable caller continuation may instead observe cancellation even
though the committed state became visible. This avoids a usable mixed owner; it does not
promise cancellation always aborts a commit. The mutation is bounded in bytes and
operation count, not operating-system I/O latency: a stalled local write/force can
delay cancellation and Resource release until that system call returns. No
filesystem-operation wall-clock deadline or interruptible-force guarantee is claimed.

Any thrown failure once mutation begins poisons the owner, including a failure
after head replacement or after in-memory publication. Both snapshots and mutations
then fail until Resource release and reopen. There is no cached-read exception.
Close failures remain suppressed under an original write/force failure; a shared
exception cannot mask its own primary failure through self-suppression.

The implementation requires JVM `ATOMIC_MOVE` and directory `FileChannel.force`.
Unsupported atomic moves do not fall back to copy/delete; unsuccessful directory
force aborts and poisons the owner. Fault hooks bracket split writes, file forces,
object/head installation, each directory force and in-memory publication.

## Explicit bounds

Callers may lower these limits; raising them above the hard ceilings is rejected.

- 128 selected history operations, including rollback
- 256 cumulatively replayed transactions, including transactions later rolled back
- 32 MiB cumulative selected seed/journal bytes read during recovery
- 64 MiB cumulatively accounted transaction/delta evidence during checked replay
- 64 MiB total directory regular-file bytes, including orphans and staging
- 512 regular files, including the lock, head, orphan and staging files
- 10 MiB per immutable object, 128 bytes maximum head framing
- Two fixed subdirectories and two fixed staging names; no arbitrary tree traversal

The unchanged pure engine further limits a transaction/state to 1 MiB, a batch to
64 transactions/8 MiB of transactions/32 MiB of accounted evidence, UTxO to 4096
entries, witnesses to 128 per transaction, CBOR depth to 12 and items to 65,536.
Fees are nonnegative uint128 and revisions uint64. A replay operation increases
revision even when its semantic content returns to the initial state.

Capacity admission reserves the complete next immutable object plus a full head
and two transient regular-file entries, even if content already exists. Selected
history, transaction and recovery work never reset on rollback. Evidence accounting
is cumulative even when an undo releases its in-memory top delta. The bound counts
encoded data and retained evidence, not exact JVM heap: bounded CBOR span retention,
immutable collections, serialization copies and one provisional batch add overhead.
No unlimited caller-controlled recovery or scan is performed.

## Filesystem trust and evidence boundary

The dedicated directory and all ancestors must be trusted and exclusively owned.
File locks are advisory; they cannot stop a writer that ignores them. Every observed
entry is checked with NOFOLLOW_LINKS, and only NoSuchFileException means absent.
Symlinks, nonregular objects and unrelated entries are rejected. These checks do
not prevent hostile symlink/rename/hard-link races, malicious in-place mutation,
replacing the lock inode, or maliciously rewinding an otherwise valid head. There
is no anti-rollback anchor outside this local directory. Concurrent external
modification is unsupported, including modification while an owner caches state.

The tests distinguish injected exceptions/cancellation from actual forked JVM
termination with `Runtime.halt`, which skips Resource finalizers. Reopen must observe
old state before head replacement and complete new state afterward. These are
process-interruption tests on the local test filesystem. They are not hardware
power-loss, kernel-crash, device-cache, network-filesystem or filesystem-corruption
proofs. Forcing files and directories expresses the NIO durability protocol; a
successful test cannot establish storage-controller power-loss guarantees.

## CLI

`restricted-replay-store init <directory> <checkpoint-only.trace.tsv>` initializes
only a validated checkpoint. The trace format is the existing read-only replay
format, with zero transaction rows. Missing/default context is rejected.

`restricted-replay-store inspect <directory>` takes the exclusive lock, checks the
entire selected history and prints one consistent state/head/revision/undo snapshot.

`restricted-replay-store commit <directory> <revision> <head-sha256> <batch.trace.tsv>`
checks the explicit persisted fence and identical checkpoint identity, then commits
all transaction rows as one atomic batch. An archived accepted-then-rejected trace
therefore rejects the entire batch; it does not leave its first transaction applied.
A zero-row batch is a checked no-op. This differs intentionally from the existing
read-only sequential corpus observation command.

`restricted-replay-store rollback <directory> <revision> <head-sha256> <transition-sha256>`
undoes only the selected checked top transition. No current-version default or
implicit branch choice is allowed. CLI durable fences are matched against the
freshly opened owner, whose session token is then used internally.

Exit codes: 0 success, 1 predicate rejection, 2 malformed/stale/resource/rollback
input, 3 unsupported projection profile, 4 internal pure failure, 5 storage/open/
corruption failure. Successful output explicitly states local persistence and
continues to disclaim full ledger, tick, instant stake, block, consensus and
hardware-power-loss guarantees. The existing `restricted-replay` command remains
read-only and unchanged.

## Validation status and fixture limit

The final durable module passes all 150 tests together, and its direct CLI gate
passes all 19 cases. All 1,294 project tests and 31 scripts have passing coverage
across preserved runs; timing failures prevent a clean uninterrupted aggregate or
serial-run claim. Fresh-archive acceptance remains separate and pending. Tests cover
both genuine archived A/B branches, exact independent final output bytes, atomic
accepted/rejected batch failure, reopen-preserved transaction and undo evidence,
ABA/session fences, concurrent writers/readers, bounded storage/work, corruption,
cancellation, poisoned owners and abrupt subprocess termination at each hook.
The evolving [verification report](restricted-replay-store-verification.md) records
observed commands, source identities, failures, corrections and remaining acceptance
gates; integrated source alone is not an acceptance claim.

No fixture supplies two successful dependent transactions. This slice does not
invent one, create keys/signatures, attach unrelated blocks or claim that missing
law as independently verified. Successful multi-transaction dependent undo remains
a future genuine-fixture gate. Existing fixture bytes and acquisition-store
semantics remain unchanged.

### Process-integration test timing profile

Each abrupt-process scenario is its own registered test and starts one independent
JVM. The process has a 30-second deadline; forced cleanup is bounded by a further
5 seconds, with a 120-second per-case test guard. Merged child output is drained
concurrently; only the first 64 KiB is retained, with overflow bytes counted and
discarded. Normal EOF drain waiting is bounded at five seconds, followed by at most
one additional five-second wait after closing/interruption if needed. A forced
or unfinished drain is an error, never a successful probe. The child classpath is resolved from the actual loaded test/module/dependency code
sources and is designed for forked or in-process tests, without an existing application
classpath file. Child JVMs use APC2/Xmx256m and
the forked module test runner uses APC2/Xmx512m. These are new test-harness guards
covering cold crypto initialization, filesystem work and observed host scheduling;
no existing protocol/production deadline is increased. Runtime overhead is reported
separately from storage behavior and is not a durability or throughput claim.

The explicit forked classpath/runtime profile has been observed in the passing
process suite. The optional `Test/fork := false` variant has not been executed and
remains unverified; code-source discovery alone is not a passed integration test.
