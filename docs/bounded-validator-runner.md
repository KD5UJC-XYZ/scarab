# Bounded in-memory validator runner

`BoundedValidatorRunner.resource[F: Async](context, peer, policy)` owns a private
`CoherentSequence.Runtime`. It exposes a read-only `snapshot` and one-shot `run`.
This slice adds offline orchestration and scripted tests, not a CLI, live runner,
durable facade or production validator. It does not modify recovery APIs.

The source base is `dccbece39e6976a9d1408848f55ef73e6a81ea13`; existing sequence
reference evidence is `ba443fbf1f71b7a42881f97a3771f95a1a056185`. That evidence was
captured after its post endpoint was known and is not continuous-runner evidence.

## Authority and transitions

The peer's ChainSync cursor and one pending envelope/block pair are transient.
Only the coordinator snapshot establishes the scoped applied tip. The supplied
anchor alone has no applied tip. Each event completes before another is requested:
fetch exact original, parse restricted input, prepare the full tuple, then publish.
There is no polling of acquisition checkpoints, prefetch queue or second mutable
tip. Empty and multi-transaction blocks use the same atomic publication path.

Backward events use a fresh snapshot fence and the existing whole-tuple rollback.
Reconnect offers only the current validated retained points through the supplied
anchor, rolls back the selected offered point, and revalidates all successors.
Origin/unoffered/outside-window points stop; no eviction or implicit reanchor.
The scope remains one supplied epoch, at most eight retained blocks, and zero
through sixteen supported transactions per block. Full-ledger and consensus
validation flags remain false. No wider transaction support is introduced.

## Stops, ownership and budgets

`Outcome` includes an authoritative final coordinator snapshot, typed `Stop`, event
attempt count, returned byte count and reconnect count. TargetReached means the
configured retained target was reached; it is not a caught-up or consensus claim.
Unsupported, rejected, internal, peer, cleanup and budget stops remain distinct.
Stale/foreign capabilities, revision exhaustion, coordinator internal errors and
undo invariant failures stop internally rather than reconnecting.

AlreadyRun during an active run is an advisory rejection, not a stable terminal
receipt: its coordinator snapshot is atomic, but telemetry is read separately.

The one-shot guard cannot be reset by cancellation or concurrent callers. A later
run returns AlreadyRun without opening a peer. Cancellation stays Cats Effect
cancellation; read `snapshot` afterward. A publication can commit before cancellation
is observed. Test barriers receive only stage labels, not mutation capabilities.
Coordinator publication remains atomic; there is no broad masking of validation.
Synchronous crypto is bounded but cannot be preempted mid-operation.

Event attempts, returned bytes and reconnect counts accumulate across reorgs and
retries. Eight bounds retained depth, not cumulative rollback/reapplications;
nonrefundable event/byte budgets bound that work. Event budget counts Await and backward events. Byte budget counts returned
header envelopes and complete returned blocks, including the payload crossing the
limit. It does not count handshake/framing traffic or failed partial fetches: the
Peer API does not expose those bytes. Maximum returned object sizes remain 65,535
bytes per envelope and 1 MiB per block. An injected Peer must enforce its own bounded
allocation/transport decoding; this runner cannot prevent an oversized allocation
already made by an arbitrary peer implementation.

The whole run has a positive cooperative work deadline <=120 seconds; target1–8, events up to256,
reconnects0–4 and cumulative returned bytes up to64MiB. Reaching the target stops
before another RequestNext. Rollback never refunds work budgets. AwaitReply and
timeouts are never reported as caught-up success.

## Conservative transport boundary

Only explicit `BoundedValidatorRunner.Unavailable` errors from an established
Peer's intersect/next/fetch operations permit reconnect. A trusted adapter must
classify availability without conflating malformed protocol data. Unexpected
exceptions from coordinator work are terminal internal outcomes.

The existing SingleProtocolConnection uses IllegalStateException for several
unrelated conditions, including EOF, deadlines and malformed data. This runner
never retries that class broadly. Existing sessions do not manufacture Unavailable,
so their ambiguous failures terminate. NoBlocks remains the existing Invalid
“expected StartBatch” response and conservatively terminates as PeerFailure.

Resource acquisition errors also terminate, even if named Unavailable: acquisition
may include failed partial-resource cleanup and Resource does not expose enough
information to safely separate those failures here. Established-peer cleanup
errors are always terminal and override normal/budget/error outcomes; they never
trigger reconnect. Deadline/cancellation waits for resource finalizers; a hung
injected finalizer can exceed the work deadline. Cleanup is not abandoned to claim
a hard wall-clock bound. External cancellation still remains cancellation, with the
surviving coordinator state readable and resource finalizers run.

## ChainSync processing deadline

`BoundedChainFollower.sessions(connection, magic, idle = 30.seconds)` illustrates
the adapter configuration for a future runner. The existing default stays5seconds;
the parameter must be positive and <=120seconds before connection acquisition.
This changes only ChainSync Idle. Handshake/can-await remain5seconds, must-reply
120seconds, and BlockFetch whole-request15seconds.

Idle begins after a forward/intersection response and continues while fetch,
prepare and publish run. Set it explicitly to a bounded processing allowance; do
not send RequestNext early to evade it. The separate run deadline still applies.
Actual adapter tests cover a six-second processing pause under configured/default
Idle, and AwaitReply without a duplicate RequestNext.

## Test and future live boundary

Public scripted tests use synthetic supplied anchors for control-flow failures.
Positive coordinator cases use exact retained valid originals under
`COHERENT_SEQUENCE_EVIDENCE`. Synthetic context mutations test rejection, not live
negative agreement or alternate valid Praos forks. The retained lane covers actual
empty/multi-transaction blocks, rollback/reapply, backpressure, cumulative budgets,
failed-fetch reconnect and cancellation around publication. A missing second
transaction input tests all-or-nothing multi-transaction publication.

A future reviewed live test must establish intersection before production resumes,
follow within a fixed four-block target, contain an actual empty block and both
test transactions in one block, and match an exact paused final reference endpoint.
Grouping/endpoint failure is preserved rather than extending the window. The
first runner has no live evidence and no durability or process-restart claim.
Future durable ownership must come through the separately reviewed facade, never
an acquisition store or deserialized coordinator capability.
