# Local sustained completion fences

The optional `sustained-durable` completion-fence interface is isolated test orchestration authority, not a consensus certificate. Existing numeric-target runs retain their semantics.

Add `--completion-fence /absolute/canonical/path`, `--fence-id` (fresh lowercase 32-byte hex process nonce), `--fence-phase A|B`, and `--minimum-depth`. Phase A creates the store with minimum depth 9 and maximum (`--blocks`) 9–12; phase B resumes with minimum at least 12 and at least three above its exact loaded depth, maximum 16. Limits remain at most 128 events, 32 MiB returned bytes and 120 seconds. Reconnects are zero so readiness identifies one peer session. Numeric maximum alone never succeeds: the runtime waits under its time bound for a fence and never publishes a block past that maximum.

The fenced peer must intersect the exact initial tip; an earlier offered point is rejected. After the initial durable snapshot and peer intersection, `node-fence-ready` binds phase, nonce, context, initial full claim and a token-free `projection` from the exact same immutable runtime snapshot. B's full `node-loaded` projection precedes peer acquisition. The controller starts production only after readiness, then demotes producers using its independently checked process protocol. It declares the actual converged point once; minimum and maximum are admission bounds, not guesses about a future exact block.

Install a new regular file atomically at the initially absent fence path. It contains exactly these eight UTF-8 LF-terminated lines, in this order, with no additional fields or whitespace:

```text
version=live-completion-fence-v1
fenceId=<process nonce>
phase=A
contextId=<bootstrap context hash>
depth=<cumulative depth>
blockNo=<absolute block number>
slot=<absolute slot>
hash=<header hash>
```

The file is limited to 2048 bytes. Canonical integers and hashes, phase/process/context, same epoch, depth bounds and bootstrap block-number offset are checked. Symlinks, rewriting, removal and atomic replacement (even identical bytes on another inode) fail. Reads compare file identity, creation/modified timestamps, length and SHA-256. The same artifact is revalidated after resource closure.

Fence adoption is serialized between durable transitions: the loop and pre-publication checks read it synchronously. If a controller installs an older fence during an already-started durable mutation, that acknowledged mutation remains; the resulting older/forked fence is terminally rejected, with no rollback, retarget or fence ACK. The honest controller must declare a point observed on the same now-keyless chain, never a guessed earlier count. A rejected post-compaction publication preserves the independently acknowledged compaction.

An arriving fence races a blocked RequestNext. An exact current point cancels that request and closes the peer. If catch-up remains, the original pending request is retained; it is never canceled and reissued mid-protocol. Events, bytes and elapsed time remain bounded. A missing or unreachable fence cannot become count-only success. Cancellation settles both racing fibers and closes owned resources.

Only exact depth/block-number/slot/hash equality can yield `node-fence-ack`. It is emitted after peer, checkpoint and journal resource finalization and includes the nonce, phase, artifact SHA-256, complete observed state/full claim, and `projectionSha256` of canonical `ValidatedRestartCapture.projection` JSON UTF-8. It asserts `resourcesFinalized: true`; it does not assert full ledger validity, monetary conservation or independent chain selection. Controller approval must additionally check the original blocks and reference oracle equality.
