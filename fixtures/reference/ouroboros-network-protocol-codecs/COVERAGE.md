# Pinned network property inventory

Status: current bounded adapter coverage; remaining work is [K2 / #49](https://github.com/KD5UJC-XYZ/scarab/issues/49).

Source: `ouroboros-network` `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`.
The compiled pinned targets' `--list-tests` output contains
[161 protocol leaves](registered-protocol-tests.txt) (SHA-256
`f642b337a15b85e45d384f9b423bf2220e34c1a94e283faff4a60aed79323437`) and
[two IO leaves](registered-io-tests.txt) (SHA-256
`990c2e63789d4be9c593c5189b10167295b6bb6e694e5f2d60ebd6d8377ee153`).
The lists retain duplicate display names. Counts below refer to registered leaves,
not complete property coverage. Each fixture manifest records its actual independent
upstream invocation and verdicts; the [fixture recipe](README.md) describes native consumers.

| Registered family | Leaves | Native adapter evidence | Remaining boundary under #49 |
|---|---:|---|---|
| BlockFetch | 23 | Bounded serialised codecs, partitions, binary compatibility and 24 original generic behavior cases through the shared production engine/runtime: 298 ranges, 694 bodies, 180 empty ranges. The ordinary inline-payload corpus adds 64 original samples and all five codec-property observations, replayed through production framing | `directPipelined 1/2`, `connect_pipelined 1–5` generated scheduling cases; broader seeds and separate IO/pipe backend outcomes. Structural mock-payload equality does not establish Cardano payload semantics |
| ChainSync | 34 | Bounded serialised codecs, partitions, binary compatibility; 24 exact generic fork cases through production engine/runtime; original direct/connect/demo/pipeline outcomes and parameters retained. The ordinary inline-payload corpus adds 64 original samples and all five codec-property observations, replayed through production framing | Broader seeds; request/collect decisions and buffer occupancy are not implied by final-chain parity or the fixed-window native profile. Structural mock-payload equality does not establish Cardano payload semantics |
| Generic Handshake | 21 | Original versions 0/1/2, version-dependent data and asymmetric version-1-only server: 792 original observations across eighteen non-generator leaves, plus nine separately labelled cross-version judgments. Native adapters exercise shared production wire/selection contracts, refusal codecs and exhaustive partitions; Cardano properties remain separate | Bounded samples do not establish generic effectful client/server, ST/IO/pipe/SimNet interpreter, transport or scheduler equivalence. Three generator-consistency leaves concern upstream test generation/shrinking |
| KeepAlive | 9 | 24 original finite server conversations; codec, split-2/3 and valid-CBOR samples | 24 original byte-limit observations match the per-state ceiling; automatic client cookie policy and independent IO-backend outcome coverage remain distinct |
| LocalStateQuery | 10 | Bounded codecs/partitions and original acquisition/query conversations through production server | Specific-point mock integer hashes cause retained whole-map exclusions; backend/interpreter equivalence is not inferred from linked Mux |
| LocalTxMonitor | 11 | Codec, partitions, valid-CBOR, codec-ID and 24 original fixed-snapshot conversations | Independent IO/pipe outcomes; upstream observed snapshot samples contain no AwaitAcquire requests |
| LocalTxSubmission | 13 | Codec, annotated codec, partitions and 24 original conversations: 147 submissions, 25 accepts, 122 refusals | Independent IO/pipe outcomes and complete state/error branch coverage |
| PeerSharing | 10 | Bounded generic codecs plus 300 genuine Cardano SockAddr cases; 24 exact generic conversations through the shared production runtime and real linked Mux, 217 requests / 985 Int items; 24 original byte-limit observations | Mock integer peers and unclamped generic replies retain their original domain; Cardano address and response-limit policies remain independently tested |
| TxSubmission2 | 15 | Codec/annotated partitions and 24 original generic conversations through the production engine/runtime, 94 ordered transactions | `connect 2` choice vectors are retained, not a replay of its scheduler; independent IO/pipe outcomes |
| ObjectDiffusion | 14 | No Scala adapter | Genuine Peras protocol, outside Scarab's advertised feature set: Peras is false, protocols 16/17 are absent. Not test infrastructure, not Leios 18/19, and not interchangeable with TxSubmission2 |
| Generic CDDL `serialise Any` | 1 | Cardano CDDL validation is separate | Haskell library/test-value roundtrip; no extra Scala mini-protocol to implement |
| IO Pipe / Socket demos | 2 | Each retains 24 original property verdicts and complete final-chain results. Pipe uses linked Mux; Socket uses actual loopback TCP with production Cardano Handshake/Mux/ChainSync | No OS-pipe or scheduler-trace equivalence. Upstream Socket uses a test-only TestVersion handshake, so Cardano negotiation is not mock-handshake wire parity |

Generic domain gaps remain visible: codec samples map 49/128 ChainSync rows,
20/24 BlockFetch rows, 13/24 TxSubmission2 rows and 18/24 PeerSharing rows to strict
Cardano values. The remaining original bytes and upstream verdicts are retained.
A generic runtime replay does not turn those strict-codec exclusions into Cardano passes.

The [ordinary codec corpus](ORDINARY-CODECS.md) retains all 128 original mock-domain
messages without Cardano coercion. It observes 640 original property invocations and
compares complete structural CBOR payloads through the shared production engines,
including 1,670 two-part and 23,447 three-part partitions. This is distinct from the
tag-24 wrapped/serialised binary-compatibility fixtures. The
[generic Handshake corpus](../../../protocol/src/test/resources/upstream/generic-handshake/README.md)
retains original property verdicts separately from native pure wire/selection checks.
Its 505 two-part and 3,545 three-part partitions preserve unknown-version skipping,
diagnostic bytes and the highest-version refusal without fallback.

Several ST/IO/direct/connect names instantiate the same polymorphic test program.
Their externally visible functional contract can share one Scala production adapter;
this does not establish interpreter, OS transport or scheduling equivalence. The three
Handshake generator-consistency leaves check QuickCheck generation/shrinking, rather
than a missing production feature. Cardano-specific negotiation fixtures come from
`cardano-diffusion`; the separate generic Handshake corpus retains the original mock
domain and does not infer interpreter or scheduler equivalence from pure selection.

## Mux and timers

`network-mux` is a separate test target. Its pinned
`network-mux/test/Test/Mux.hs:89–121` registers send/receive, bidirectional and compatibility,
one/two-protocol Queue/Pipe/Socket, buffering, starvation, demux, start/stop/restart,
close, trailing bytes, pure exceptions, SDU codecs and generator laws.
The current upstream SDU adapter matches 34/40 rows and retains six explicit
zero-length encoder-policy differences. Local MuxSuite/MuxDeadlinesSuite cover
many runtime invariants. The separate
[Mux lifecycle corpus](../../../network/src/test/resources/upstream/mux-lifecycle/README.md)
now retains 24 close and 24 trailing-byte cases with 96 original IO/IOSim verdicts.
Its production-Mux adapter compares complete observed response prefixes and buffered
byte order. Local controlled cancellation/cleanup checks stay distinct from those
native invocations. Original start/stop/restart and pure-exception properties were
also executed as native evidence; Scarab does not expose the same internal activation
API. Exact scheduling is outside functional equivalence. Broader transport/backend,
broader starvation and other applicable properties remain in #49.

`network-mux/test/Test/Mux/Timeout.hs:33–68,122–175,283–325,339–403` registers
single and sequential timer laws. A portable Scala counterpart must retain elapsed
bounds, value/cancellation and cleanup observations. Native/Alternative, GHC and
Windows implementation branches do not require porting Haskell timer internals.
The pinned IO tolerance code is −0.001/+0.3 seconds with at most five failures;
its old prose mentions a different positive tolerance. The native repeated IO test
is disabled upstream with issue #2612's CPU-interference explanation. Scarab's
existing deterministic deadline tests are local controls, not new upstream adapter
passes. No exact real-clock or scheduler-trace equivalence is claimed.

The practical local follow-up adds `MuxSeededTrafficSuite` (eight seed/SDU profiles, 320 complete
messages and 256 observed paired SDU-write groups across duplex channels; the former message
writer produced 160 groups) and `DeadlineCleanupSuite`
(writer-finalizer publication order and partial-receive cancellation/lock reuse). These supplement
observable transport and timer cleanup coverage without adding upstream invocation counts.
`BulkControlCharacterizationSuite` now requires timely successful KeepAlive responses alongside full
bulk delivery using bounded paced transport and opaque bodies; #259 retained the former FIFO
deadline miss. `MuxEgressSuite` checks SDU turns during a large message, duplex FIFO, global fences
and staged-capacity/cancelled-admission behavior. These are local virtual-time controls, not a native
starvation-property pass, host benchmark or observed background disconnect. The
[measurements and refactor scope](../../../docs/design/network-limits.md#bulkcontrol-scheduling-characterization-49)
record the implemented writer-fairness contract under [#258](https://github.com/KD5UJC-XYZ/scarab/issues/258).

ObjectDiffusion scope is established by
`ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ObjectDiffusion/Test.hs:82–101`
and `cardano-diffusion/lib/Cardano/Network/NodeToNode.hs:281–295,490–494` at the pin.
All unsupported feature and applicable adapter gaps remain under #49; this inventory
is not a whole-suite completion declaration.

The [PeerSharing finite/byte-limit corpus](../../../network/src/test/resources/upstream/peer-acceptance/README.md)
retains all four direct/connect/channel-ST/channel-IO verdicts per finite case.
Its three over-sized generic replies and 94 negative generated function results
are retained without Cardano policy coercion. KeepAlive and PeerSharing ceilings
are compared with actual upstream state limits; this is not a new mux enforcement
or generic-Int-to-Cardano-address claim.

## Bounded original starvation-generator replay

The [Mux starvation corpus](../../../network/src/test/resources/upstream/mux-starvation/README.md)
retains 32 consecutive original Uneven inputs: 22 native property passes and 10 implication discards.
Scarab replays 18 applicable original response1/response1 conversations with the unchanged
first 100-header ordering assertion; four native passes above its 2.5 MB message ceiling remain explicit
mock-domain exclusions. Twelve original unequal response pairs are separately labelled local
controls. Complete native CBOR bytes/hashes are checked. A controlled nonvacuous-overlap observation
supplements the original normalization, whose initial/final-run trimming can otherwise admit a serial
trace. This closes the bounded generator-replay gap, not broad transport or exhaustive seed coverage.
