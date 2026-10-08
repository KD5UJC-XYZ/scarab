# Bounded acquisition checkpoint publication

This packet persists the existing `BoundedChainFollower` acquisition window. It
stores original ChainSync header envelopes and exact BlockFetch block bytes, not a
ledger state, consensus tip, transaction validity judgment or whole-node snapshot.
The retained window remains at most eight blocks. No reference node or live
network is needed by its public tests.

## Interfaces and identity

`AcquisitionCheckpoint` is a pure bounded binary codec. Its context pins a concrete
anchor slot/hash, a caller-supplied source digest, a genesis digest, network magic,
and the exact `conway-pv9-header11.2-acquisition-v1` profile. The outer SHA-256 binds
that context, the publication generation and every original envelope/block byte.
Lengths/counts are checked before allocation, trailing bytes reject, and decoding
rebuilds the checkpoint through `BoundedChainFollower.checked`. Header/body
identity, parent/slot continuity, block numbers and expected header version are
rechecked. The profile label is an expected context, not proof of ledger PV9.

The caller must obtain the expected context independently of the checkpoint file.
The genesis digest must bind the exact trusted genesis inputs; the source digest
must identify the intended upstream private cluster/run or source configuration.
Neither an unauthenticated peer address nor a context read back from the same
checkpoint is an independent trust anchor. This packet does not authenticate a
remote peer or discover genesis identity over the wire.

`AcquisitionCheckpointStore[F]` exposes the expected context, a verified snapshot,
and `save(expectedRevision, checkpoint)`. A revision is generation plus digest.
Stale caller revisions reject. `NioAcquisitionCheckpointStore.resource` has
explicit create/resume modes; resume optionally requires a caller-held expected
revision and rejects a different one. Without that independent token, replacing
the entire directory with an older internally valid checkpoint cannot be detected.
The generation is concurrency evidence, not a tamper-proof monotonic counter.

The existing `checkpoint.source[F].identity` hashes the complete retained original
byte sequence and anchor. **Extension, rollback and fork replacement change that
identity.** An existing `NioSegmentStore` is therefore not silently reused for a
new branch/window; its source compatibility checks still apply. The pinned upstream
context stays fixed while the acquisition window changes. The new store does not
duplicate segment manifests, block indexing or ledger persistence.

## Publication and recovery boundary

The NIO interpreter follows the existing segment-store durability pattern: one
exclusive directory owner lock, full staging write, file `force(true)`, atomic
replacement of `checkpoint.bin`, then directory `force(true)`. There is no fallback
to a non-atomic move. Cancellation is masked over publication. A write/install
exception poisons that owner until close/reopen; the caller must treat a failed
save as indeterminate if the atomic replacement may already have happened.

Only three regular files are allowed: empty `lock`, `checkpoint.bin`, and
`checkpoint.tmp`. Each checkpoint file is bounded to 9 MiB, total data to 18 MiB.
Symlinks, parent path components, unexpected entries and oversized files reject.
This is a single-owner local directory model, not protection from an adversary
concurrently replacing ancestor directories despite the advisory lock.

Reopen accepts only a complete, digest-checked, context-matched published file and
revalidates all retained original bytes. It never promotes staging data. A bounded
staging residue is removed only after the published file validates. Missing or
corrupt published data fails closed even if staging contains valid newer bytes.
An interrupted first publication is not silently initialized again. There is no
recursive cleanup of unrelated paths or automatic retention beyond this window.

`BoundedChainFollower.persistedResource` obtains the initial state and revision
from the store. Every accepted intersection rollback, RollBackward and completed
RollForward is published before in-memory state advances. Incomplete BlockFetch
batches are never published. Publication errors are fatal and do not enter peer
reconnect retries. Canceling an acquisition keeps the last completed publication
available to a fresh store/follower owner.

## Offline evidence and remaining acceptance

The focused suite covers exact-byte save/reopen, stale revisions/context, modified
payloads with both stale and recomputed digests, truncation and bounds, exclusive
ownership, all injected publication boundaries, partial initialization, staging
non-promotion, rollback/fork reapply and cancellation after one completed block.
Reopen tests release the old store and construct a new owner from disk; they do
not merely reconnect the same follower's in-memory `Ref`.

Fault injection demonstrates old-or-new complete publication under the tested NIO
filesystem operations. It is not power-loss, hardware durability or hostile-disk
attestation. Filesystem support for atomic replacement and directory forcing is
required; failures remain explicit.

The implementation was tested offline from committed main `a8732da` in a separate
worktree, with a pinned JDK container restricted to 2 CPUs/2 GiB and no network.
`scalafmtCheckAll` and all 185 public app tests passed, including the 12 checkpoint
cases. Independent source review and its requested cancellation/post-install/identity
test additions completed without remaining blockers. Private build/test receipts
are under `/home/<user>/cardano-acquisition-checkpoint-verification-20261008`.
No private fixture corpus, prebuilt native helper or live cluster was needed.
The broader serial CLI gate runner was not completed: an early invocation lacked
its generated runtime classpath; that artifact was generated in the successful
final build, but only the stated app/format checks are claimed for this packet.

The later live acceptance must run one Scala process to acquire and publish, end
that process, then launch a separate Scala process against the same independently
identified reference cluster and checkpoint directory. It must prove retained
candidate selection and successor/fork handling from revalidated disk bytes.
That live test is not part of this packet and needs review plus a resource slot.
No Scala live-process recovery, whole-node recovery, ledger recovery, consensus
validation, signature validation or production crash-safety claim is made here.
