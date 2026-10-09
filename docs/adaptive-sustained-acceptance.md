# Adaptive local sustained acceptance

This candidate replaces guessed block-count stopping points with one immutable completion point for each process. It is an isolated test protocol, not consensus validation. Earlier fixed-target launchers and their failed evidence remain unchanged. No successful live acceptance is recorded for this candidate.

## Required sequence

1. Bootstrap the private reference fixture. Stop both producing processes using identity-checked pidfd TERM, confirm their exits, and restart the same databases without forging credentials. Preserve all observed originals and process records. Observe all three reference nodes at the same point. This convergence observation does not prove that every network queue is empty.
2. Capture a bounded exact range and select its last two linked, empty original blocks. Acquire their exact predecessor once through native LocalStateQuery. Retain all four original CBOR payloads and native derived JSON from that acquisition. Replay the two originals into a capacity-two durable seed. This protocol does not claim that the validator ran during bootstrap forging.
3. Start process A while the reference producers are keyless. Check its exact intersection, full durable claim, and full projection. Record a conservative current-clock slot before restoring production. Every A successor must independently decode to a later slot. After A reaches the minimum depth of nine, demote both producers again and observe convergence. Select the observed completion point once, within depth 9–12, and publish the process/context-bound fence atomically. Do not move it to make a mismatch pass.
4. Require A's exact fence ACK after all runtime resources close, then successful process exit and retained checkpoint/journal bytes. Start a distinct process B by strict journal resume. Its full loaded claim and projection must equal A's; loading does not create an acknowledgement receipt. B must be ready while both reference producers are keyless.
5. After B readiness, submit the two disposable local transactions while both reference producers remain keyless. Then restore reference production. B must include at least three independently verified blocks produced after readiness, with total depth at most sixteen. Demote producers, select B's observed point once, and require the exact immutable fence ACK, exit and retained storage.
6. Acquire the exact final point once. The native helper checks point/block brackets around epoch, whole UTxO, chain-dependent protocol state and parameters queries. No latest-tip substitution, fallback acquisition, or retry is allowed. After verified helper exit and owned-container removal, map pinned native bytes to the Scala oracle manifest.
7. Restore production before starting the network-disabled audit so the two-epoch growth observation overlaps that work. Replay every original independently, compare the online projection and full supported reference projection, and verify the exact two transaction bodies together in the newly produced B suffix. Finish the independent audit and immutable source/binary pin checks within their original deadlines.

An immediate successor is retained and checked within the declared phase bounds; it is never silently dropped. A delayed historical block fails the readiness-slot predicate. A runtime that has already advanced beyond a declared fence fails without rollback, retargeting or fence ACK. Numeric maximum alone cannot signal success.

## Explicit proposed bounds

The fresh fixture uses 1,000 slots per epoch at 0.1 seconds per slot: 100 seconds per epoch. Security parameter 5 and active-slot coefficient 0.05 remain unchanged. The derived 400-slot randomness stabilization window remains unchanged; the relative candidate freeze cutoff becomes slot 600. These are new fixture parameters, not changes to a running cluster.

The proposed workload limit is **500 seconds**, with an absolute **600-second** cleanup boundary. This explicitly increases the previous 480-second workload proposal by 20 seconds. All stage deadlines are mapped once to a monotonic clock and bounded by the case deadline. Current UTC and genesis geometry, not a stale tip alone, determine epoch headroom.

| Post-admission reservation | Seconds |
| --- | ---: |
| Pre-query, role transitions, seed, readiness, handoff, submission and production opportunity, including epoch margin | 83 |
| B catch-up, exit and storage retention | 15 |
| Final native query including owned cleanup | 25 |
| Restore production | 5 |
| Two-epoch growth opportunity, overlapping audit and final pin checks | 210 |
| Final case guard | 25 |
| Total | **363** |

Admission must occur by elapsed **137 seconds** and have at least **83 seconds** of actual epoch headroom. The audit has a 65-second cap and final pin verification 10 seconds; these fit inside the overlapping 210-second tail reservation. Block arrivals and sufficient tail growth are stochastic: these bounds are admission limits, not timing guarantees. Bootstrap and pre-admission range capture consume the same overall case budget.

The complete accepted window is bounded by capacity two, sixteen blocks, 128 reported event-budget units and 32 MiB returned original bytes. Each operation receives only its remaining allowance; independent process limits do not reset aggregate accounting. Receive-byte overflow can only be detected after a bounded message read. The range command counts received ChainSync/BlockFetch messages, excluding the handshake; the existing runtime counts bounded next-event attempts. The controller sums these reported charges without resetting them. This is not a uniform count or bound on every network frame.

One reference cluster uses at most 2 CPU/2 GiB; sequential Scala processes use 1 CPU/1 GiB. The native query runs in a separate 1 CPU/1 GiB container with no network, read-only pinned executable/source/libraries, a shared owned Unix socket and private output. Its execution and cleanup share one absolute 25-second boundary from container operations beginning. The 20-second supervised helper may need up to two seconds of internal termination; external cleanup gets only the remaining allowance. Receipt presence alone is never success. Unknown creation outcomes require owner-scoped cleanup.

## Evidence and claim boundaries

Original native payloads stay separate from derived native JSON. The mapping records source hashes and explicitly identifies tip records as the acquired first/final point brackets, not CLI output. Parameter bytes must remain identical across endpoints. Context manifests bind only pre-state; the post-state oracle is interpreted after independent original replay.

The independent audit covers the implemented bounded projection, original-body grouping, counters, nonce fields, registrations and parameter endpoint equality. It does not establish full ledger validation, monetary conservation, reward-seed admission, consensus chain selection, or power-loss durability. Retained-data compatibility tests and synthetic transport tests are reported separately from actual acquisition and live execution.

See [runtime fence contract](sustained-completion-fence.md) and [native oracle contract](../reference/sustained-query-client/README.md). Final controller source review, integration checks and bounded live authorization are separate gates. This document itself does not authorize execution.
