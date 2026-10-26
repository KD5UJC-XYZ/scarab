# BlockFetch peer coordination (#148)

Status: implemented; combined local gates passed, PR #289 awaits review and merge.

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
are insufficient to define this behavior. The GSV comparator's 5% tie band is not
transitive, so both GSV orderings (ranking and preferred-peer admission) use
`HaskellSort.sortBy`, the reference's GHC `Data.List.sortBy` algorithm, which
reproduces the native order for such a relation; a `java.util` sort may reject it.

One `FetchCoordinator` belongs to the shared `BlockPool`, and its decision loop mirrors
`fetchLogicIterations` (`BlockFetch/State.hs`): an iteration registers the grace delay,
waits until the trigger fingerprint (our chain's head, each peer's candidate head, each
peer's fetch status with its in-flight set) differs or the grace expires, decides for every
peer from its whole current ChainSync candidate, acts, and sleeps out the rest of its
mode's decision interval. Fetched blocks, GSVs and the fetch mode are non-trigger state.
The node owns the loop for its lifetime, independently of session registration, and receives
its defects directly (`NodeKernel.hs:382–393`, consensus `82ecba329`). A standalone peer or
test node owns its own loop resource. `InboundGovernor` requires that explicitly owned pool;
it cannot allocate an unowned fallback. The test node keeps one loop across all of its connections
and reconnects. Each session's candidate is an empty fragment until it
announces one, as consensus registers it.

The decision pipeline is the reference's: `filterPlausibleCandidates`,
`selectForkSuffixes` and `filterNotAlreadyFetched` (`FetchCandidates`), then
`filterNotAlreadyInFlightWithPeer`, `prioritisePeerChains`,
`filterNotAlreadyInFlightWithOtherPeers` and `fetchRequestDecisions`
(`BlockFetchPolicy.decide`). Every block of a plausible candidate's fork suffix that is
neither fetched nor in flight is fetchable, so fork headers declined while their chain was
not yet preferred are requested once it is (#430). A request is added to the peer's
request slot (`addNewFetchRequest`), merged with one its client has not taken, and is in
flight from then on; declines change nothing. Each session's fetch client takes the slot
(`acknowledgeFetchRequest`), sends the request's ranges in order and collects each in a
fiber of its own while it takes the next. A range's block budget is its fragment's length;
bytes are bounded per message by the codec and per protocol by the multiplexer's ingress
queue, not per range. The client's only deadlines are the reference's per-state ones
(Busy and Streaming 60 s per message, Idle unbounded), with no whole-range deadline.
Publishing ChainSync headers to the follower never waits for a body: ChainSync and
BlockFetch progress independently. Global targets default to one in either mode and retain
the upstream preferred-peer handover exception, which can temporarily exceed a target.

The selected-chain input is a read-only follower snapshot. Transport fibers never
write back to the consensus selection cache or acquire its application lock. Actual
ChainSync batches are capped at 2048 to fit the current plus three queued batches
inside the 8192-header history; the configured read-ahead remains an upper bound. Announced
Shelley sizes are the reference's `estimateBlockSize` (body + header + 10 bytes), and
Byron uses the advertised node-to-node size hint (`byronHeaderBlockSizeHint`), retained on
each decoded header outside its signed bytes. Raw-only decoding returns `UnsizedByron`;
joining an actual hint produces `Header.Byron`. Full-block decoding derives the hint from
the original block annotation plus nine bytes, as `Byron/Ledger/Block.hs:141–175,274–338`
at consensus `82ecba329` does. One peer's hint cannot change another peer's in-flight bytes.

Per-peer request/byte limits use upstream high/low-water hysteresis and permit the
last complete block to cross the high watermark. A pool claim is bookkeeping for a
session that waits for a block; it never changes what was requested.
The unacknowledged slot merges requests with the latest GSV and limits; acknowledgement
retains those limits for every range and receipt of that request (`ClientState.hs:760–793`).
Each add computes Busy against its high watermark, while each receipt or rejected batch
computes Ready against its captured low watermark (`ClientState.hs:723–746`). GSV sampling
alone cannot change the stored status. Terminate takes precedence over a queued request in
the same state transaction (`ControlMessage.hs:47–55`); state publication and waiter wakeup
form a finite uncancelable operation.

## Pipeline and lifetime

Production connections allocate `BlockFetchClient.resource`. Concurrent submissions
are bounded (100 by default, matching `Cardano/Network/NodeToNode.hs:212` at the pin), go onto the wire in FIFO order and collect responses
in that order. A checked original enters the shared fetched state before its estimated byte
credit is returned (`Client.hs:274–295`). NoBlocks retires the rejected fragment and request
in one transaction. BatchDone validates the expected count before successful completion;
too few blocks remain a typed protocol failure (`Client.hs:236–240,300–304`).
The compatibility `apply` constructor remains serial for callers requiring it.

Each collector owns its typed error scope and ends with a value (ADR 0017). A
cancelled submitted request aborts the connection and settles every pending request;
no subsequent request can consume its replies. Cancellation before admission leaves
the connection reusable. Unknown exceptions remain typed defects. The first pipeline
fault is retained before sibling cancellation and connection closure, preserving an
invalid-header cause rather than replacing it with a generic aborted connection.

An admitted download remains waitable through preparation/cache publication. A
session cannot mistake the gap between a request's addition and its pool claim for a
missing block. If a downloader fails, its blocks leave the flight and the next decision
requests them again; a healthy peer that has not been asked is not blamed. Cache provenance and
invalid-block attribution remain owned by the existing BlockPool session resources.

## Evidence and acceptance

The independently exported [native corpus](../../fixtures/reference/block-fetch-scheduling/README.md)
retains 289 scheduling observations (256 generated, 33 boundaries) and eight GSV
observations. `UpstreamBlockFetchPolicySuite` compares rankings, fragment boundaries,
refusal payloads, watermarks, durations and deadline probabilities without masks.
`FetchCandidatesSuite` checks the consensus adapter boundary. Runtime tests retain
24 original generated protocol cases/298 ranges and cover outgoing FIFO pipelining,
timeout, cancellation and resource release.

Coordinator laws cover the decision trigger, shared admission, preferred handover,
in-flight holes, borrower waiting, range credit release, hysteresis and mode transitions. The controlled
workload in `FetchCoordinationBenchSuite` characterizes bulk/deadline targets with
heterogeneous virtual peers; these are service-model measurements, not public-network
throughput claims. Actual commands and results belong in [performance](../performance.md)
and [compatibility](../compatibility.md). Required combined gates are Docker `check`
and private `relay --seconds 300`; nightly soak is separate.

Final runtime `e67e5117e` passed Docker `check` (5,941 tests, 17 optional-data skips),
291 script controls and the private five-minute relay. Native decision, runtime,
coordinator, rollback and typed-failure tracks received independent agent review.
The relay's observed KeepAlive idle reconnects remain a bounded investigation in
[#292](https://github.com/KD5UJC-XYZ/scarab/issues/292); the passing gate is not a
zero-reconnect claim. Full receipts are in the compatibility evidence log.
