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

## Offline evidence

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
`scalafmtCheckAll` and all 187 public app tests passed, including the 12 checkpoint
cases. Independent source review and its requested cancellation/post-install/identity
test additions completed without remaining blockers. Private build/test receipts
are under `/home/<user>/cardano-acquisition-checkpoint-verification-20261008`.
No private fixture corpus, prebuilt native helper or live cluster was needed for
those tests. All 68 Python harness tests passed. Six relevant public CLI gates also
passed: network, ChainSync, ChainSync session, BlockFetch, TCP direct range and
keepalive direct range. The two new CLI rejection cases and mounted atomic
publication probe passed. These are the stated relevant gates, not a claim that
every public serial gate was run.

## Separate-process acceptance adapter

`scripts/private_cluster_acquisition_restart.py` uses the existing private-cluster
lifecycle without restarting reference nodes. Its preflight runs the standalone
`lab.AcquisitionRestartCapture probe` in the pinned JVM image on the actual
task-owned checkpoint mount. The probe creates, publishes, closes and reopens a
synthetic checkpoint, requiring atomic replacement and directory force before
the harness generates a Cardano environment.

The harness reads exact genesis/configuration/topology bytes and reference
container/image identity directly, builds deterministic digest recipes, and pins
profile, network magic and a concrete queried anchor. These pins are held outside
the checkpoint directory and rechecked between process phases and afterward.
Process A acquires/publishes two originals, emits its revision, exits and is
observed stopped. That expected revision is saved in a separate host receipt.
Only then does a new container/JVM process B reopen the same store with that exact
expected revision and the independently retained context, revalidate the two
originals and acquire two successors. A reports 0-to-2 and B reports 2-to-4; both
require the expected retained-tip intersection, with no network reconnect retry.

The evidence includes Docker container IDs, host PIDs/start/finish timestamps,
JVM process nonces, source/class hashes, independently pinned context, loaded and
published revisions, exact original prefix/suffix bytes, and stored-file hashes.
The upstream context stays fixed while the checkpoint's byte-derived source
identity must change on extension. No segment store is used or silently reused.
Checkpoint files and all raw logs/bytes remain private outside Git.

Each JVM is limited to 1 CPU/1 GiB alongside the existing 3 CPU/6 GiB reference
container. The default workload is 420 seconds with a 600-second overall budget.
The normal final growth/convergence and cleanup receipts remain required. Run only
with an authorized resource slot, a clean committed isolated checkout and compiled
runtime classpath:

```sh
python3 scripts/private_cluster_acquisition_restart.py \
  --reference-image "$PINNED_REFERENCE_IMAGE" \
  --scala-repo "$ISOLATED_COMPILED_REPO" \
  --output "$NEW_PRIVATE_EVIDENCE_DIRECTORY" --seconds 420
```

This is graceful A-exit/B-reopen acceptance, not forced JVM termination during
publication, power-loss recovery, reference restart or ledger recovery.

## Completed bounded live acceptance

The authorized isolated run tested committed source
`0d0a68b91f8e6a93d44f43c25bae8edcf0030078` against the pinned private reference
image. It passed in 127.987 seconds within the 420-second workload and 600-second
overall budgets. Process A (host PID 22383) exited before process B (host PID
22583) started; their container IDs and JVM nonces were distinct. B reopened A's
exact independently retained generation-4 revision and published generation 8.

B loaded exactly A's two original header/block byte pairs, selected retained tip
slot 60, fetched successor slots 64 and 65, and published four originals with the
two-original prefix unchanged. The upstream context remained fixed while the
byte-derived acquisition source identity changed. An independent check rebuilt
both checkpoint binary encodings exactly, verified their revision/file hashes,
and recomputed the context recipes from the captured raw public configuration.
The final published file matched the reconstructed B bytes.

The reference cluster continued from block 7 to block 49 and epoch 0 to epoch 2,
with final convergence. Cleanup verified that the task's containers and network
were gone. Private receipts remain outside Git in
`/home/<user>/cardano-acquisition-restart-live1-20261008`; the independently checked
summary is in the verification directory's `live1-checked-summary.md`.

This demonstrates graceful separate-process disk reopen and successor acquisition
for the bounded supported window. It does not demonstrate live fork replacement,
forced termination during publication, power-loss durability, ledger or whole-node
recovery, consensus validation or signature validation. No segment store was used.


## Abrupt death after acknowledged publication adapter

`scripts/private_cluster_acquisition_abrupt.py` preserves the graceful adapter and
adds a separate acceptance mode. A publishes and verifies its final snapshot,
emits the exact revision and originals, then holds the store owner for at most 60
seconds without further writes. Before signaling, the controller independently
reconstructs the checkpoint bytes against the pinned context, saves its expected
revision outside the checkpoint mount, and confirms that the owner is running.
It sends SIGKILL to the immutable owned container ID, requires exit 137 without
OOM, and verifies that checkpoint bytes stayed unchanged. B then uses the existing
exact-revision reopen, prefix and successor checks.

The resource and cleanup bounds are unchanged. This tests abrupt process death
after acknowledged publication only. It does not interrupt publication or establish
power-loss durability. The codec, store and follower APIs are unchanged.
