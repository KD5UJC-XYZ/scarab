# Network resource limits (G5)

- **Status**: As built; final gates recorded in [compatibility](../compatibility.md).
- **Issue**: [#35](https://github.com/KD5UJC-XYZ/scarab/issues/35).
- **Reference**: ouroboros-network `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`, the existing
  protocol source pin; actual interoperability uses reference node 11.1.3.

Networking mirrors ouroboros-network in structure and behaviour ([ADR 0008](../adr/0008-networking-mirrors-ouroboros-network.md)).
Every mini-protocol state's time limit is the pinned reference's (`timeLimits*`, cited per row), except
the BlockFetch client's cumulative request time (the BlockFetch client rework, #430). The TxSubmission
byte/item profile (the TxSubmission alignment, #477), the BlockFetch client's aggregate bounds (#430), the
ChainSync intersection-list count (the ChainSync alignment) and the other local bounds that the matrix
lists remain explicit until they are aligned: each difference from the reference recorded here is
alignment work, not a permitted deviation, unless ADR 0008 lists it as approved. The node already had ingress budgets, finite outbound queues, bounded codecs and
state timers. G5 adds the missing SDU deadlines, checks completed-message byte limits and bounds
TxSubmission's retained request state. It changes no persisted format or original transaction bytes.

## Reference and implementation matrix

The pinned `ouroboros-network/api/lib/Ouroboros/Network/Protocol/Limits.hs:105-115` defines
`smallByteLimit=65535`, `largeByteLimit=2500000`, `shortWait=10s` and `longWait=60s`.
State names refer to wire agency, so KeepAlive `Client` is the server waiting for a client ping.

| Component/state | Pinned reference | Scarab and explicit local policy |
|---|---|---|
| Handshake | 5760-byte message; node-to-node 10s per state (`timeLimitsHandshake`, `Handshake/Codec.hs:80-88`), node-to-client no state limit (`noTimeLimitsHandshake`, `:91-99`); 10s handshake-SDU budgets for both | 5760-byte message, node-to-node 10s receive phase (which also limits silence before the first byte), node-to-client none; 10s per SDU read and write in both |
| Established mux read | Wait indefinitely for first header bytes; then complete that SDU within 30 s | Same partial-frame rule, using scoped `Pull.timed`; trickled header/payload bytes do not restart the deadline |
| Mux write | 30s for one SDU on the application bearer, 10s on the handshake bearer (`ConnectionHandler.hs:94-102,335,379,412,451`); a batched write gets SDU-count×timeout (`Bearer/Socket.hs:224`) | 30s per SDU, 10s for the handshake lane, which is the reference's handshake bearer. Each write is one SDU with its own budget: no cross-protocol batch (`Egress.hs:164-186` batches up to 100 SDUs or 131072 bytes under SDU-count×timeout), so no donation from later SDUs. This difference remains |
| Mux framing | Header 8 bytes, payload at most 65535 accepted; SDUs sent are cut at the bearer's `sduSize`, 12288 for sockets (`Bearer.hs:83-86`, node-to-client `Snocket.hs:455-456`) | Same: `Mux.Config.sduSize` 12288 for sending, `maxSduPayload` 65535 for acceptance; the decoder rejects oversized and zero-byte SDUs, partial EOF and invalid headers |
| ChainSync active states | 65535 bytes; Intersect/CanAwait10s; Idle `ChainSyncIdleTimeout`, default 3373 s (`Cardano/Network/Protocol/ChainSync/Codec/TimeLimits.hs:59-64`, `Cardano/Network/Diffusion/Configuration.hs` `defaultChainSyncIdleTimeout`); MustReply uses peer trust and per-state random timeout | Same: server Idle3373s; each untrustable MustReply state draws a fresh uniform601–911s budget; trustable outbound peers wait indefinitely. The local-root activation captures trustability; inbound peers are untrustable. Partial message reads retain the sampled budget. Explicit fixed test profiles remain supported. |
| BlockFetch Idle/Busy | 65535bytes; Idle indefinite, Busy 60 s (`BlockFetch/Codec.hs:59-68`) | Same byte bound, Busy 60 s and server Idle indefinite |
| BlockFetch Streaming | 2500000bytes per message,60s per reply | Same per-reply wire limit; no cumulative request deadline or aggregate byte/range-count cap. Full candidate fragments determine request sizes; the server streams any range it holds (ouroboros-consensus `82ecba32`, `MiniProtocol/BlockFetch/Server.hs`). |
| TxSubmission Init/Idle | 65535bytes; indefinite (`TxSubmission2/Codec.hs:78-88`) | 65535bytes in codec and runtime channel; indefinite in both states |
| TxSubmission blocking IDs | 2500000bytes; indefinite | Existing stricter 524400-byte/eight-item profile, left to the TxSubmission alignment (#477); indefinite wait |
| TxSubmission nonblocking IDs / bodies | 2500000bytes;10s | Same 10 s policy table;524400-byte/eight-item local profile (#477). Current runtime server emits only blocking ID requests; body replies now use 10 s rather than 60 s |
| TxSubmission retained inventory | Retained+requested IDs ≤configured maximum 10; no duplicate/unadvertised body IDs in one request | Same pure credit rule checked before mempool lookup or reply-batch truncation; server also refuses excess or duplicate offers before calling its sink |
| KeepAlive Client/Server | 65535bytes; Client 97 s, Server 60 s | Same message/state bounds; existing 30 s ping interval is separate scheduling policy |
| PeerSharing Idle/Busy | 5760bytes; Idle indefinite, Busy 60 s | Same; reply cardinality ≤request; published reply capped 10 by governor policy. The governor's logical3/10s budget is separate from the draining 60 s wire request |
| Node-to-client | Reference local interfaces have large queue/message allowances; the protocols run with `runPeer`, without state time limits (ouroboros-consensus 82ecba32 `Ouroboros/Consensus/Network/NodeToClient.hs`, `mkApps`), and the handshake with `noTimeLimitsHandshake` (`Handshake/Codec.hs:91-99`) | No state time limit for the local protocols or the handshake; existing per-protocol local size limits and the shared SDU deadlines apply. This is a local-process interface, not a newly claimed public networking profile |
| Leios tools | Prototype profiles, outside ordinary node-to-node protocol set | Existing tool-specific bounds remain; G5 does not claim prototype reference limits are published |

Source locations at the pin:

- `ouroboros-network/framework/lib/Ouroboros/Network/ConnectionHandler.hs:78-102` and
  `network-mux/src/Network/Mux/Bearer/Socket.hs:72-102,182-230` (SDUs), and
  `network-mux/src/Network/Mux/Codec.hs:57-72` (including rejection of zero-length SDUs).
- `cardano-diffusion/protocols/lib/Cardano/Network/Protocol/Handshake/Codec.hs` and
  `ouroboros-network/framework/lib/Ouroboros/Network/Protocol/Handshake/Codec.hs` (handshake).
- `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/Codec.hs:56-63` and
  `cardano-diffusion/protocols/lib/Cardano/Network/Protocol/ChainSync/Codec/TimeLimits.hs:43-87`.
- `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/BlockFetch/Codec.hs:33-70`,
  `KeepAlive/Codec.hs:83-116`, `PeerSharing/Codec.hs:127-162`, `TxSubmission2/Codec.hs:50-93`.
- `ouroboros-network/lib/Ouroboros/Network/TxSubmission/Outbound.hs:107-173,185-208` and
  `TxSubmission/Inbound/V2/Policy.hs:94` (ten outstanding IDs).
- `cardano-diffusion/lib/Cardano/Network/NodeToNode.hs`, `chainSyncProtocolLimits` and its
  neighbours; `network-mux/src/Network/Mux/Ingress.hs` (queue budgets and overrun).

## Queue ownership and byte checks

`IngressLimits` already derives byte budgets from the reference's maximum in-flight work, with 10%
safety margin. Defaults: ChainSync 462000, BlockFetch 23068694, TxSubmission 721424,
KeepAlive 1408, handshake/PeerSharing 5760 bytes. Raising read-ahead above 300 increases the ChainSync
budget. Each direction/mini-protocol has its own counter; a slow consumer never blocks the demux.
On overrun, the connection ends with `NetworkError.IngressLimit`. Zero-byte SDUs are rejected by
the framing codec, so counting positive payload bytes also bounds the number of queued SDUs.

Egress admission holds at most 65 messages/fences by default: the configured 64 queue slots plus
one writer allowance. One semaphore covers the inbox, staged per-channel queues and the current
write, so importing work never frees admission capacity. The default message ceiling is 2500000
bytes; an explicit positive `Mux.Config.maxMessageBytes` also sets the aggregate segmentation
bound, independently of the uint16 SDU bound. Protocol-specific ingress and receive limits remain
separate. `send` waits for a credit. No additional byte budget is introduced without a measured need.
Node-to-client's separate queue ceiling is 4294967295 bytes, matching the reference local-interface
allowance; it should not be described as an unbounded queue.

`MuxChannel.recv(maxBytes)` checks both incomplete buffers and the consumed length of a completed
message. A valid oversized message that arrives in one chunk is refused too. Bytes for later
messages remain buffered: two 100-byte messages coalesced into 200 bytes are legal under a 100-byte
per-message limit. Codec structural limits remain an independent bound on decoding work.

## Typed failures and cancellation

The reader's timer is disabled between SDUs, starts on a partial frame, stays fixed across progress,
and resets only after a complete SDU when another partial frame begins. Timed pulling retains the
stream's resource scope and cancels its source/timer on completion, timeout or mux release. The
writer bounds the actual bearer write, including a stalled handshake flush. A timeout completes
`Mux.closed` with `NetworkError.SduTimeout(Read|Write)`; it does not throw a domain exception.

`TxSubmissionPolicy` is pure and immutable. Its closed `Violation` distinguishes bad counts,
acknowledgement/credit, invalid blocking style, duplicate/unadvertised body IDs and excess/duplicate
offers. The runtime raises `NetworkError.TxSubmission(violation)` in the calling fiber. Requesting
the same body in later messages before acknowledgement remains allowed, as upstream does.

SDU timeout is a transient transport failure. A resource/agency violation is peer misbehaviour.
Within these mux/transaction scopes, unexpected library failures retain the original
`NetworkError.Defect`, except an exception thrown by a decoder on the connection's bytes, which is
`NetworkError.DecoderDefect` and closes only that connection ([ADR0017](../adr/0017-fibers-end-with-values.md));
the node network-fault classifier bypasses peer blame for `Defect`;
expected bearer `IOException` remains a recoverable closure. An uncaught exception from a local
transaction source is a defect even if its Java type is `IOException`, because mux transport errors
already arrive as typed values. The transaction background fiber establishes
its own typed scope under [ADR0017](../adr/0017-fibers-end-with-values.md). Broader independent
session/storage/listener work remains tracked by [#180](https://github.com/KD5UJC-XYZ/scarab/issues/180).

## Mux error values (#82)

The framing codec returns `Either[Mux.Error, A]`, with distinct cases and numeric payloads for
header fields, local limits, exact frame lengths and partial-header/payload EOF. Stream failures
publish neither a new decoder nor partially decoded output. Wire bytes, limit values, validation
precedence and the existing per-frame implementation are unchanged. `segmentBounded` reports its
65535-byte ceiling for nonpositive message limits too. For those inputs an invalid SDU limit still
wins; a message ceiling above 65535 is refused before checking the SDU limit.

The reader retains that cause in `NetworkError.Framing`; local decoder setup and writer encoding
retain it in `MuxSetup` and `MuxWrite`. The node carries the same value in the corresponding
`PeerError` case instead of rendering it into `Protocol` or `Unavailable` text. Framing remains
misbehaviour; setup/write retain their previous transient/backoff policy. This is an error-value
migration, not a change to that policy; the rest of the error-channel migration remains [#82](https://github.com/KD5UJC-XYZ/scarab/issues/82).
The scoped fiber ownership and SDU timer rules above still apply; only presentation calls `message`.

## Bulk/control scheduling characterization (#49)

`MuxSeededTrafficSuite` adds eight recorded local seed/SDU profiles (1, 7, 256 and 65535-byte
SDUs), 320 messages over two protocols, both roles and both directions. Bounded queues combine
pairs of actual writes and repartition their bytes across headers and payloads. The checks compare
complete per-channel FIFO bytes and observe all 256 paired SDU-write groups (the old whole-message
writer produced 160 groups in #259). They are local
production-Mux controls, not additional exported Haskell property verdicts or exhaustive seeds.

`DeadlineCleanupSuite` observes cancellation before teardown can hide it: a blocked writer finalizer
prevents publication of `mux.closed`, and a timed-out partial receive releases its lock and retains
exactly its buffered prefix for immediate reuse. These are portable observable cleanup laws;
identical timer implementations and exact real-clock scheduling are not asserted.

`BulkControlCharacterizationSuite` first measured the historical FIFO behavior in #259 using production Mux,
BlockFetch server/client and a direct KeepAlive ping through a paced, bounded transport under
virtual time. The payloads are opaque transport bytes, not valid ledger blocks or a historical chain.
Both profiles stay within existing message, range, ingress and request bounds. The bulk backlog
and ping admission are observed before releasing the writer; no admission delay is hidden.

| Bulk workload | Controlled rate | Bulk-only completion | Completion with ping | Ping arrival at responder |
|---|---|---|---|---|
| 40 × 60 KiB bodies | 32 KiB/s | 75.019 s | 75.019 s | 75.019 s |
| 3 × 2000000-byte bodies | 64 KiB/s | 91.565 s | 91.565 s | 91.565 s |

With that historical writer, the admitted ping timed out at its existing 60 s response deadline while bulk replies
continued arriving; the complete ordered bulk payloads still matched. These are simulated service-time
observations, not host throughput benchmarks. The direct probe deliberately keeps the connection
open to observe late arrival. `KeepAliveClient.pinger` aborts Mux after a failed ping by source
inspection; the characterization does not execute that background disconnect. The current assertions
now require successful pings and retain complete bulk comparisons under the same workloads.
The confirmed gap and acceptance criteria are tracked in
[#258](https://github.com/KD5UJC-XYZ/scarab/issues/258), separately from retained-intersection #152.

### Fair egress as built (#258)

The scheduler selects network traffic on one connection; it does not coordinate coding agents.
Peers and local clients can observe cross-protocol latency, SDU interleaving and connection endings.
Node-to-node and node-to-client servers use the same Mux. Protocol APIs, message schemas, original
message bytes, ingress behavior and persisted formats are unchanged.

The pinned reference `network-mux/src/Network/Mux/Egress.hs:35–59,149–213` defines no starvation and
roughly equal service under equal demand, rotating remaining SDUs between channels. Scarab's private
`MuxEgress` implements that useful contract with an immutable round-robin of `(protocol, role)` lanes.
Each turn writes one complete SDU; a lane's later messages remain behind its current message.
A newly admitted lane joins the rotation, including while another lane has a large message in flight.
There is no control-protocol priority rule or reproduction of Haskell's exact thread schedule.

Admission is a soft byte budget per lane (`egressBufferBytes`, 0x3ffff, the reference's
`egressSoftBufferLimit` in `network-mux/src/Network/Mux.hs`): `send` is queued while fewer bytes than the
limit are buffered on its lane, so one message may pass the limit, and later senders on that lane wait in
arrival order. Bytes are released as each SDU is written. Cancellation while waiting for admission retains
nothing; once admitted the message stays queued. A flush marker is not counted: the writer stops
importing at that marker, fairly drains its finite predecessor set, acknowledges it, then admits
later commands to the rotation. Thus a live `flush` covers every earlier submission across lanes,
without waiting for later traffic. Existing abort wakeups and first typed close reasons are retained.
The writer, rather than a caller capability, owns outbound work across fibers.

**Write-deadline policy:** each actual bearer write now contains one SDU and receives 30 s, or 10 s
for Handshake. This follows the reference's per-SDU/batch-count rule with a batch count of one.
A multi-SDU message no longer lends later SDUs' budgets to a stalled first write: the old two-SDU
60 s stall is now refused after 30 s. With no contention, two writes using 29 s then stalling take
59 s before refusal. Scheduling waits between turns do not consume bearer-write time; existing
protocol receive/request deadlines remain separate. No progress notification resets a write timer.

The same paced profiles now require a successful KeepAlive response in less than 5 s, full ordered
bulk bodies and at most 1 s extra bulk completion time. Observed virtual response times are about
1.876 s / 1.000 s; bulk completion remains about 75.019 s / 91.565 s. These are simulated service
times, not host benchmarks. `MuxEgressSuite` separately admits another lane during a large
in-progress message, checks both duplex roles and FIFO, blocks the last pre-fence write to rule out
an early acknowledgement, blocks later traffic to rule out an extended fence, and verifies staged
capacity plus cancelled-waiter reuse. Existing abort/lifecycle tests retain first-cause cleanup.

Fair service applies after admission within a fence prefix. A full admission budget can still delay
`send`; KeepAlive's response timer starts after that call. A flush deliberately prevents later traffic
from passing its predecessors. This is not an unconditional latency bound under arbitrary rates or
full queues. It meets the useful scheduler contract with Scarab's fs2 design; aligning the multiplexer's structure with the
reference ([ADR 0008](../adr/0008-networking-mirrors-ouroboros-network.md)) is [#477](https://github.com/KD5UJC-XYZ/scarab/issues/477) work. Broader upstream/property coverage remains #49.

## Same-connection ChainSync recovery (#152)

When another session advances the local retained window past a candidate's intersection, the
follower searches for a new intersection through the same `Peer` and protocol clients. The tracked
candidate reference remains the same. This follows `NoLongerIntersects` in pinned consensus
`82ecba329d7d054340bf707d44fe6e9ac27cec40`,
`Ouroboros/Consensus/MiniProtocol/ChainSync/Client.hs:1499–1511`.

`Peer.intersect` stops new read-ahead requests, collects outstanding ChainSync replies and waits for
their block-prefetch workers to publish. An ordered drain marker follows those batches. Consuming
a batch returns its permit before a cancellation can interrupt the handoff; the marker returns the
producer's final permit. Old buffered instructions are discarded before `FindIntersect`. A successful
intersection starts a fresh header gate and resets progress before read-ahead resumes with the same
bounded capacity. KeepAlive and TxSubmission are not restarted.

Typed producer/prefetch failures and original defects are landed in the caller's error scope rather
than hidden by recovery. Drain completion retains success, failure or cancellation, including for a
later cleanup call. A peer that shares no offered point still returns `NoIntersection`; a header that
does not extend the new intersection and a rollback outside the retained chain still fail under the
existing policies. Recovery does not authorize a rollback past the immutable anchor.

Draining may wait for an outstanding reply at the peer's tip or a pending block fetch. Existing
protocol deadlines and session cancellation still apply; recovery promises no additional fixed
wall-clock bound. Tests cover real duplex recovery with header validation and concurrent
TxSubmission, repeated intersection/gate replacement, clean quiescence, failure/defect propagation,
cancellation cleanup and a genuinely disjoint fork. The relay assertion remains unchanged.

## Validation

`MuxDeadlinesSuite` runs on `TestControl`: idle 90 s, partial header/payload, 5 s slow-loris progress,
completed-plus-partial frames in the same chunk, independent subsequent deadlines, EOF, writer
per-SDU/handshake budgets, source cancellation and coalesced/oversized complete messages.
`TxSubmissionLimitsSuite` generates 300-command hostile traces, checks credit/FIFO against an
independent model, and executes real mux conversations for credit exhaustion, bad requests,
overshare, duplicates, body deadlines, blocking idle and original defect preservation.
`PeerSuite` pins transient versus misbehaved attribution. A 100000-SDU traversal uses a real 256 KiB
worker and asserts the observed execution thread. These suites fail on uncaught fibers.
Full check, extended decoder fuzz and private soak/relay results belong to the compatibility evidence
log; no public-network run is claimed for this change.

### Original upstream generator replay

The [native starvation corpus](../../network/src/test/resources/upstream/mux-starvation/README.md)
retains 32 original Uneven samples and their unchanged property outcomes (22 passes, 10 implication
discards). Eighteen original conversations replay through Scarab with the upstream SDU-order
assertion; four oversized native passes remain explicit mock-domain exclusions. Twelve applicable
unequal generated pairs are supplemental local controls because the pinned native property uses
response1 twice. Original CBOR bytes, hashes, header observations and provenance are retained.
Controlled overlap separately rules out the original assertion's vacuous serial-trace case. These
are bounded original-generator checks, not native/Scala thread-schedule identity or all-seed coverage.
