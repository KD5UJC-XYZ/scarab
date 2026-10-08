# Bounded follower live acceptance, 2026-10-08

Passed against the verified local official Cardano 11.1.3 image on dev-host, using
source commit `aeb5b1c3a243854500623b6be3d0d6e0834b88af`. This is bounded Conway
original-byte acquisition and explicit resume, not consensus or ledger validation.

The reference query supplied block 2, slot 41, hash
`8e75c7dc49e9e1604749a72adddfc203797e1f2aa64b396c88376e33b460957c`.
Scala acquired blocks 3 and 4, reconstructed a checked checkpoint from the original
bytes, and opened a fresh ChainSync connection. After intersection, the adapter
injected one local exception before RequestNext. The follower released that
connection, reconnected, reintersected and acquired blocks 5 and 6.

| Block number | Slot | Phase |
| --- | --- | --- |
| 3 | 63 | Initial |
| 4 | 79 | Initial; retained resume tip |
| 5 | 108 | Resumed |
| 6 | 122 | Resumed |

Both resume offers contained slots 79, 63 and 41 in that order. Both responses
selected slot 79, hash
`7aa4a0379748df54d7fa9dbfc450aa5ee9b4ff94fd6b401b1de65f7e98e110f1`.
The initial two original header/block pairs exactly equal the final prefix.
Both phases returned `targetReached`; initial/resumed event counts were 4/6 and
returned payload accounting was 3,420 bytes in each phase. This excludes mux
framing and other wire traffic. No transaction was submitted in this exercise.

Independent read-only verification used a separate Python standard-library bounded
CBOR parser to extract original header spans and recompute Blake2b-256 header and
body-component hashes. It checked parent, slot and block-number linkage from the
queried anchor, all four body size/hash commitments (four bytes per empty body),
the repeated original prefix and both selected points. All 630 tracked-file hashes
and 29 follower class-file hashes matched the source receipt. Those hashes establish
artifact identity, not independently reproduced source-to-bytecode compilation.

## Isolation, timing and cleanup

The unchanged private-cluster lifecycle checked official binary SHA-256 pins,
created an internal Docker network, disabled public peers and peer sharing, and
used tmpfs-only disposable credentials and chain state. No host ports were
published; the Scala container used the cluster network namespace and a read-only
source mount with no key mount. The cluster and Scala caps summed to 4 CPUs/7 GiB.
No host tools or images were installed or downloaded.

The task-owned namespace was `cardano-private-cd2cb62045f7`. The run completed in
125.997 seconds under a 240-second workload and 600-second overall limit. The
existing three-node harness observed Conway ledger protocol version 9.0, block
growth from 6 to 50 and epoch 0 to 2 with final convergence. Cleanup independently
attempted removal of preflight/Scala containers, stopped and removed the reference
container, removed its network, and confirmed empty remaining-container and
remaining-network lists. The unrelated OpenClaw container was not touched.

## Exact local receipts

All raw evidence stays outside Git at
`/home/<user>/cardano-follower-live-20261008-a/`:

- `scala-follower.md`: exact original envelope/block hex, anchor, offered/selected
  points, outcome reasons/counters and acquisition-only flags.
- `follower-anchor.md`, `follower-assessment.md`: queried anchor and acceptance.
- `source-receipt.md`: source commit, all tracked source hashes and follower class hashes.
- `binary-hashes.md`, `image.md`, `engine.md`: verified reference artifact/environment.
- `commands.md`, `container.md`: execution and isolation settings.
- `result.md`, `observations.md`, `protocol-parameters.md`: reference observations.
- `cleanup.md`, `timing.md`: cleanup and time verification.
- `independent-review.md`: transcribed reviewer conclusion, with separate implementer
  cleanup confirmation; the original review is in this task's agent conversation.

The launcher is `scripts/private_cluster_follower.py`; four offline launcher guards
passed before execution. The adapter's five unit tests and follower's 17 tests
were previously verified in an offline 2 CPU/2 GiB container with an independent
cache. No public push was performed.

The injected failure is deliberately local and occurs between protocol operations.
It is not a reference-node crash or a mid-packet disconnect. This four-block window
does not establish long-running synchronization, full-chain validation, transaction
validity, signatures, leadership, or chain selection. Scripted rollback, partial
batch and cancellation tests remain distinct from this live acceptance.
