# Network resource limits (G5)

- **Status**: As built; final gates recorded in [compatibility](../compatibility.md).
- **Issue**: [#35](https://github.com/KD5UJC-XYZ/scarab/issues/35).
- **Reference**: ouroboros-network `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`, the existing
  protocol source pin; actual interoperability uses reference node 11.1.3.

Networking keeps its fs2 structure under [ADR0008](../adr/0008-networking-as-implemented.md).
Resource policy follows the reference where possible; the existing stricter local profiles below
remain explicit. The node already had ingress budgets, finite outbound queues, bounded codecs and
state timers. G5 adds the missing SDU deadlines, checks completed-message byte limits and bounds
TxSubmission's retained request state. It changes no persisted format or original transaction bytes.

## Reference and implementation matrix

The pinned `ouroboros-network/api/lib/Ouroboros/Network/Protocol/Limits.hs:105-115` defines
`smallByteLimit=65535`, `largeByteLimit=2500000`, `shortWait=10s` and `longWait=60s`.
State names refer to wire agency, so KeepAlive `Client` is the server waiting for a client ping.

| Component/state | Pinned reference | Scarab and explicit local policy |
|---|---|---|
| Handshake | 5760-byte message; 10s state/handshake-SDU budgets | 5760-byte message, 10s receive phase, 10s per SDU write; receive phase also limits silence before the first byte |
| Established mux read | Wait indefinitely for first header bytes; then complete that SDU within 30 s | Same partial-frame rule, using scoped `Pull.timed`; trickled header/payload bytes do not restart the deadline |
| Mux write | 30s for one SDU; a batched write gets SDU-count×30s | Same batch budget; protocol 0 uses10s per SDU |
| Mux framing | Header 8 bytes, payload at most 65535 | Existing decoder rejects oversized and zero-byte SDUs, partial EOF and invalid headers; chunks are at most 16384 bytes |
| ChainSync active states | 65535 bytes; Intersect/CanAwait10s; configured Idle; MustReply uses peer trust and per-state random timeout | 65535 bytes; Intersect/CanAwait10s; server Idle 3373 s. Existing MustReply 300 s remains local policy rather than reference trustable-indefinite/untrustable-uniform601–911s |
| BlockFetch Idle/Busy | 65535bytes; Idle indefinite, Busy 60 s | Same byte bound and Busy 60 s; server Idle 3373 s is existing stricter local policy |
| BlockFetch Streaming | 2500000bytes per message,60s per reply | Same; existing additional whole-request 120 s and aggregate 10000000 raw bytes/max 128 blocks in the client; server refuses ranges above 2160 blocks |
| TxSubmission Init/Idle | 65535bytes; indefinite | 65535bytes in codec and runtime channel. Existing Init60s/Idle 3373 s local timeouts remain |
| TxSubmission blocking IDs | 2500000bytes; indefinite | Existing stricter 524400-byte/eight-item profile; indefinite wait |
| TxSubmission nonblocking IDs / bodies | 2500000bytes;10s | Same 10 s policy table;524400-byte/eight-item local profile. Current runtime server emits only blocking ID requests; body replies now use 10 s rather than 60 s |
| TxSubmission retained inventory | Retained+requested IDs ≤configured maximum 10; no duplicate/unadvertised body IDs in one request | Same pure credit rule checked before mempool lookup or reply-batch truncation; server also refuses excess or duplicate offers before calling its sink |
| KeepAlive Client/Server | 65535bytes; Client 97 s, Server 60 s | Same message/state bounds; existing 30 s ping interval is separate scheduling policy |
| PeerSharing Idle/Busy | 5760bytes; Idle indefinite, Busy 60 s | Same; reply cardinality ≤request; published reply capped 10 by governor policy. The governor's logical3/10s budget is separate from the draining 60 s wire request |
| Node-to-client | Reference local interfaces have large queue/message allowances | Existing per-protocol local limits and shared SDU deadlines still apply. This is a local-process interface, not a newly claimed public networking profile |
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

The egress queue already holds at most 64 messages, each bounded by the 2500000-byte mux-message
ceiling. Sending waits for room. No additional byte budget is introduced without a measured need.
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
`NetworkError.Defect`; the node network-fault classifier bypasses peer blame for that constructor;
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
complete per-channel FIFO bytes and observe all 160 paired transport groups. They are local
production-Mux controls, not additional exported Haskell property verdicts or exhaustive seeds.

`DeadlineCleanupSuite` observes cancellation before teardown can hide it: a blocked writer finalizer
prevents publication of `mux.closed`, and a timed-out partial receive releases its lock and retains
exactly its buffered prefix for immediate reuse. These are portable observable cleanup laws;
identical timer implementations and exact real-clock scheduling are not asserted.

`BulkControlCharacterizationSuite` measures current FIFO behavior using production Mux,
BlockFetch server/client and a direct KeepAlive ping through a paced, bounded transport under
virtual time. The payloads are opaque transport bytes, not valid ledger blocks or a historical chain.
Both profiles stay within existing message, range, ingress and request bounds. The bulk backlog
and ping admission are observed before releasing the writer; no admission delay is hidden.

| Bulk workload | Controlled rate | Bulk-only completion | Completion with ping | Ping arrival at responder |
|---|---|---|---|---|
| 40 × 60 KiB bodies | 32 KiB/s | 75.019 s | 75.019 s | 75.019 s |
| 3 × 2000000-byte bodies | 64 KiB/s | 91.565 s | 91.565 s | 91.565 s |

In both cases the admitted ping times out at its existing 60 s response deadline while bulk replies
continue arriving; the complete ordered bulk payloads still match. These are simulated service-time
observations, not host throughput benchmarks. The direct probe deliberately keeps the connection
open to observe late arrival. `KeepAliveClient.pinger` aborts Mux after a failed ping by source
inspection; the characterization does not execute that background disconnect. The assertions pin
this undesired baseline and must become successful-ping assertions when fairness is implemented.
The confirmed gap and acceptance criteria are tracked in
[#258](https://github.com/KD5UJC-XYZ/scarab/issues/258), separately from retained-intersection #152.

### Refactor scope

The scheduler selects network traffic on one connection; it does not coordinate coding agents.
The selection is internal, but peers and local clients can observe cross-protocol latency, wire SDU
interleaving and connection endings. Node-to-node and node-to-client servers use the same Mux;
ledger rules, persisted formats and mini-protocol message schemas need no change.

The pinned reference `network-mux/src/Network/Mux/Egress.hs:35–59,149–213` defines fair service and
rotates remaining SDUs between channels, assembling bounded write batches. Scarab currently queues
whole messages (`Mux.scala` outbound setup/channel send), then writes all their SDUs together
(`writeLoop`). A likely implementation changes that outbound admission/selection/writer plumbing,
optionally extracting a private typed scheduler state. Ingress, framing codecs and protocol APIs
can remain as built. The work is localized but medium–high difficulty because it must preserve:

- FIFO per `(protocol, role)` and indivisible SDU framing, while giving other channels bounded turns.
- Bounded pending work including staged messages; current queue capacity plus one writer-held
  message is the admitted-work baseline. Draining the queue into an unbounded map defeats it.
- Connection-global `flush` fences on a live connection: all earlier admitted messages must finish
  before acknowledgement, and later traffic must not starve the fence. Abort must wake waiters.
- Cancellation-safe admission credits, the first typed close reason and caller-scoped capabilities.
- Explicit write-deadline policy: current whole-message writes get SDU-count × per-SDU budget.
  Smaller write quanta must not silently change that contract or extend work indefinitely.

Message-level priority alone cannot interrupt a large in-progress write. Fair SDU turns or bounded
batches address that case without reproducing upstream thread schedules or activation APIs, in
keeping with [ADR0008](../adr/0008-networking-as-implemented.md). #258 requires successful control
traffic alongside full ordered bulk delivery, fence/backpressure/abort regressions, independent
review and the ordinary network gate. This branch characterizes the gap; it does not fix the writer.

## Validation

`MuxDeadlinesSuite` runs on `TestControl`: idle 90 s, partial header/payload, 5 s slow-loris progress,
completed-plus-partial frames in the same chunk, independent subsequent deadlines, EOF, writer
batch/handshake budgets, source cancellation and coalesced/oversized complete messages.
`TxSubmissionLimitsSuite` generates 300-command hostile traces, checks credit/FIFO against an
independent model, and executes real mux conversations for credit exhaustion, bad requests,
overshare, duplicates, body deadlines, blocking idle and original defect preservation.
`PeerSuite` pins transient versus misbehaved attribution. A 100000-SDU traversal uses a real 256 KiB
worker and asserts the observed execution thread. These suites fail on uncaught fibers.
Full check, extended decoder fuzz and private soak/relay results belong to the compatibility evidence
log; no public-network run is claimed for this change.
