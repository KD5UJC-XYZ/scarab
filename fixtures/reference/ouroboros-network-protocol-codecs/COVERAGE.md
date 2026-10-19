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
| BlockFetch | 23 | Bounded serialised codecs, partitions, binary compatibility and 24 original generic behavior cases through the shared production engine/runtime: 298 ranges, 694 bodies, 180 empty ranges | `directPipelined 1/2`, `connect_pipelined 1–5` generated scheduling cases; ordinary wrapped-codec split/valid-CBOR families; separate IO/pipe backend outcomes |
| ChainSync | 34 | Bounded serialised codecs, partitions, binary compatibility; 24 exact generic fork cases through production engine/runtime; original direct/connect/demo/pipeline outcomes and parameters retained | Wrapped-codec split/valid-CBOR families; request/collect decisions and buffer occupancy are not implied by final-chain parity or the fixed-window native profile |
| Generic Handshake | 21 | Separate Cardano-specific properties exercise production negotiation, query, refusal and simultaneous open | Generic versions 0/1/2, version-specific arbitrary data, asymmetric unknown-version server, RefuseReason/ordinary codecs and partitions remain unadapted. Mock versions cannot be silently substituted for Cardano 14/15/16 |
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

Several ST/IO/direct/connect names instantiate the same polymorphic test program.
Their externally visible functional contract can share one Scala production adapter;
this does not establish interpreter, OS transport or scheduling equivalence. The three
Handshake generator-consistency leaves check QuickCheck generation/shrinking, rather
than a missing production feature. Cardano-specific negotiation fixtures come from
`cardano-diffusion` and do not close the generic Handshake group above.

## Mux and timers

`network-mux` is a separate test target. Its pinned
`network-mux/test/Test/Mux.hs:89–121` registers send/receive, bidirectional and compatibility,
one/two-protocol Queue/Pipe/Socket, buffering, starvation, demux, start/stop/restart,
close, trailing bytes, pure exceptions, SDU codecs and generator laws.
The current upstream SDU adapter matches 34/40 rows and retains six explicit
zero-length encoder-policy differences. Local MuxSuite/MuxDeadlinesSuite cover
many runtime invariants, but are not claimed as adapters of those other generated
properties. Their reference observation/adapter work remains in #49.

`network-mux/test/Test/Mux/Timeout.hs:33–68,122–175,283–325,339–403` registers
single and sequential timer laws. A portable Scala counterpart must retain elapsed
bounds, value/cancellation and cleanup observations. Native/Alternative, GHC and
Windows implementation branches do not require porting Haskell timer internals.
The pinned IO tolerance code is −0.001/+0.3 seconds with at most five failures;
its old prose mentions a different positive tolerance. The native repeated IO test
is disabled upstream with issue #2612's CPU-interference explanation. Scarab's
existing deterministic deadline tests are local controls, not new upstream adapter
passes. No exact real-clock or scheduler-trace equivalence is claimed.

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
