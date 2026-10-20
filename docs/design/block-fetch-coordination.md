# BlockFetch peer coordination (#148)

Status: implemented on the feature branch; combined gates and independent review pending.

## Reference and boundary

The target is the Praos bulk/deadline policy in ouroboros-network
`2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`, `BlockFetch/Decision.hs`,
`BlockFetch/DeltaQ.hs` and `Network/DeltaQ.hs`, with the consensus candidate
boundary in ouroboros-consensus `82ecba329`. Full Genesis selection remains #33.
[ADR 0008](../adr/0008-networking-as-implemented.md) still permits Scarab's own
transport implementation; this change compares decision values and observable
protocol/resource behavior, without requiring identical thread schedules.

## Ownership and decisions

`BlockFetchPolicy` and `FetchGsv` are pure. The caller supplies validated fragments,
consensus comparison ordinals, salted peer ties and one snapshot of in-flight work.
The pinned GSV distribution is degenerate: KeepAlive RTT/2 with a fixed 4Mbps
serialization estimate and time-dependent smoothing. It is not an adaptive bandwidth
estimator. A resource-owned updater samples changed KeepAlive estimates independently
of header progress at the pinned Praos decision interval (10ms); older header
announcements cannot replace newer samples. The native oracle records the actual ascending bulk and descending
deadline comparator directions; comments describing a generic preferred-first sort
are insufficient to define this behavior.

One `FetchCoordinator` belongs to the shared `BlockPool`. Outbound peers, inbound
duplex clients and single-block recovery use it. Its pure decision and reservation
commit is atomic; wire effects run after the reservation is installed. Mode changes
wake pending offers. Global targets default to one in either mode and retain the
upstream preferred-peer handover exception, which can temporarily exceed a target.
They are not per-block duplicate caps.

The selected-chain input is a read-only follower snapshot. Transport fibers never
write back to the consensus selection cache or acquire its application lock. The
candidate boundary checks plausibility, retained intersection, suffix and downloaded
holes before admission. Peer candidates survive empty replies and rollbacks within
the retained history. Queued offers retain the candidate that made their headers
fetchable, so a later rollback cannot strand a previously published header. Actual
ChainSync batches are capped at 2048 to fit the current plus three queued batches
inside the 8192-header history; the configured read-ahead remains an upper bound. Announced Shelley sizes use body + header + 10 bytes, and
Byron uses the advertised node-to-node size hint, retained outside signed headers.

Per-peer request/byte limits use upstream high/low-water hysteresis and permit the
last complete block to cross the high watermark. Pool claims may introduce holes:
actual contiguous requests are reconciled before submission; surplus fragments are
released and offered again rather than exceeding reserved request credit.

## Pipeline and lifetime

Production connections allocate `BlockFetchClient.resource`. Concurrent submissions
are bounded (100 by default, matching `Cardano/Network/NodeToNode.hs:212` at the pin), go onto the wire in FIFO order and collect responses
in that order. Per-block observations return estimated byte credit immediately;
BatchDone/NoBlocks returns request credit before signature preparation completes.
The compatibility `apply` constructor remains serial for callers requiring it.

Each collector owns its typed error scope and ends with a value (ADR 0017). A
cancelled submitted request aborts the connection and settles every pending request;
no subsequent request can consume its replies. Cancellation before admission leaves
the connection reusable. Unknown exceptions remain typed defects. The first pipeline
fault is retained before sibling cancellation and connection closure, preserving an
invalid-header cause rather than replacing it with a generic aborted connection.

An admitted download remains waitable through preparation/cache publication. A
session cannot mistake the gap between reservation and pool claim for a missing block.
If another downloader fails, recovery retries through the same coordinator rather
than blaming a healthy peer that has not yet been asked. Cache provenance and
invalid-block attribution remain owned by the existing BlockPool session resources.

## Evidence and acceptance

The independently exported [native corpus](../../fixtures/reference/block-fetch-scheduling/README.md)
retains 289 scheduling observations (256 generated, 33 boundaries) and eight GSV
observations. `UpstreamBlockFetchPolicySuite` compares rankings, fragment boundaries,
refusal payloads, watermarks, durations and deadline probabilities without masks.
`FetchCandidatesSuite` checks the consensus adapter boundary. Runtime tests retain
24 original generated protocol cases/298 ranges and cover outgoing FIFO pipelining,
timeout, cancellation and resource release.

Coordinator laws cover shared admission, preferred handover, holes, publication
waiting, terminal credit release, hysteresis and mode transitions. The controlled
workload in `FetchCoordinationBenchSuite` characterizes bulk/deadline targets with
heterogeneous virtual peers; these are service-model measurements, not public-network
throughput claims. Actual commands and results belong in [performance](../performance.md)
and [compatibility](../compatibility.md). Required combined gates are Docker `check`
and private `relay --seconds 300`; nightly soak is separate.
