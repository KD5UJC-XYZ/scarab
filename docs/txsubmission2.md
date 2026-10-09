# Bounded Conway TxSubmission2 publisher

Implementation base: `1e534a91a635547b50a37a32cd1d88f429640cc2`.
This implements the transport portion of [ADA submission contract v1](ada-submission-contract-v1.md).
It does not admit transactions, validate ledger state, sign, submit through a CLI,
open endpoints, or claim remote acceptance or inclusion. Live reference-node
interoperability remains an integration gate.

## Boundary and lifetime

All types below are in `lab.network`. `RelayOffer` is in `network`; effectful
contracts and limits are in `network-runtime`, which depends on `network` and core only.

```scala
final case class RelayOffer(transactionId: Bytes, advertisedSize: Long)
final case class RelayLimits(
  maxTransactions: Int = 8,
  maxOriginalBytes: Int = 524288,
  maxLifetime: FiniteDuration = 30.seconds
)
trait RelaySource[F[_]]:
  def acquireBatch(limits: RelayLimits): Resource[F, RelayLease[F]]
trait RelayLease[F[_]]:
  def offers: Vector[RelayOffer]
  def original(transactionId: Bytes): F[Option[Bytes]]
```

Use `TxSubmission2Session.resource(transport, config).use(_.run(data, source, onEvent))`.
`onEvent` is optional and receives only IDs and event kinds. A successful `Report`
holds at most 128 events and a request count; it contains no originals.

Each connection acquires one finite lease. Before advertising, it snapshots each
offer's original exactly once, verifies its exact body-span BLAKE2b-256 and the
actual outgoing GenTx size, and checks aggregate bounds. The structural body
extractor is not an admission validator. It accepts a bounded four-member
Conway envelope (definite or indefinite), retains its original members, and never
re-encodes the body. An indefinite envelope must end immediately after its fourth
member with exactly one break and no trailing bytes.
The upstream admission owner must supply the contract's checked signed transaction.
A faulty source cannot replace the bytes later in the session: there are no
source lookups on peer requests. No unadvertised ID is served.

The source owner must enforce current-generation eligibility and **at most two
concurrent leases globally**. The source's resource finalizer releases its pin;
it must be prompt and cancellation safe. The session cannot enforce a global
limit over independently constructed source instances. Integration owns the
shared permit pool and reconnect policy. New eligible work requires a new
connection/lease; this transport deliberately has no unbounded publication loop.

Final acknowledgement plus a blocking request with no remaining offers produces
`Done`. An acknowledgement may discard a transaction that was never requested;
it is not an acceptance receipt. `Announced`, `BodiesWritten`, `Acknowledged`
and `Finished` events must remain distinct from follower inclusion.

## Reference identity and scope

Read from the retained CHaP source archives associated with the target reference
release (node 11.1.3, CLI 11.2.3.0). These are measured source hashes, not a new
binary build identity or proof that a live node accepted these fixtures.

| Archive | SHA-256 |
| --- | --- |
| ouroboros-network-1.2.0.0.tar.gz | f94167122d6d7956206626afff2d90ce2637d4c82af8dff8784fae227ddbad70 |
| ouroboros-consensus-4.2.1.0.tar.gz | 160af19b249dbb65c8d84cf74a6c475b2080a5b16b1c30c3c709f3e6065f0972 |

Archive roots are omitted from the member paths below.

| Member | SHA-256 |
| --- | --- |
| protocols/lib/Ouroboros/Network/Protocol/TxSubmission2/Codec.hs | 24a31f4ec1204a18b34ebc18c172c383c8f58cfd5ada9564d7b3df70dab101fc |
| protocols/lib/Ouroboros/Network/Protocol/TxSubmission2/Type.hs | f64cf37640cef9f87dfe1ca1c21a6885366a3cfb3277cbab33b85e50453a17a1 |
| lib/Ouroboros/Network/TxSubmission/Outbound.hs | 44d558fe1ecacf8f15785a9fd3b1064d8892168c55928722830cb8a6115e1724 |
| api/lib/Ouroboros/Network/Protocol/Limits.hs | 85d22f3f9af5c94c5dc67897860a10a1432a02a4574b6c4ad0c9cc5394b04a36 |
| ouroboros-consensus-cardano/src/ouroboros-consensus-cardano/Ouroboros/Consensus/Cardano/Node.hs | be94c0d8908555d9d0a038ea2b46136b03688ffafa892f8e1c3f267c21a4ae40 |
| ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Node/Serialisation.hs | 4f066f345175c1ccdde7e09a079c9d5cf930f5e090e7eb1888dad1c0468f3732 |
| ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Mempool.hs | cb9d5c540ac313a0f96f41d40b4c586c8ceeeb4b30e2c2c6e6eac65d1576ef91 |
| ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/HardFork/Combinator/Mempool.hs | 7ea79e9c2140216b3cd8b9e948e214bf8b7828ab2aebe5ca81f6181e90fe7089 |
| ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/HardFork/Combinator/Serialisation/Common.hs | cbe9e8ec47aa61f031e9b729dd861474a3a19640623c7c36819330df1c5921ae |
| ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/HardFork/Combinator/Serialisation/SerialiseNodeToNode.hs | db41f10e4c0df75700593d1a4ea5567430ba4125a742496165ed39d8aa491910 |

`Cardano/Node.hs` maps NtN14 to CardanoNodeToNodeVersion2. The hard-fork
serializer uses `[eraIndex, value]`; Conway is index 6. Shelley GenTx uses
CBOR-in-CBOR, and its ID uses the era CBOR hash encoding. The protocol is mux ID 4;
the publisher is the initiator/client even though the consumer drives requests.
The existing handshake must actually negotiate NtN14 and the supplied descriptor;
a query, refusal, different network magic or unsupported version cannot activate it.

## Wire fixtures and advertised size

The codec and scripted-peer tests contain **source-derived literal fixtures**,
not generated native captures. The synthetic empty-map transaction body is not a
ledger-valid transaction. Its fixture original is `84a0a0f5f6`; its body ID is
`d36a2619a672494604e11bb447cbcf5231e9f2ba25c2169177edc941bd50ad6c`.

| Message | CBOR shape / literal |
| --- | --- |
| Init | `[6]`, `8106` |
| Request IDs | `[0, blocking, uint16 ack, uint16 request]`, `8400f5000a` |
| Reply IDs | `[1, indefinite [[conwayId, uint32 size], ...]]` |
| Request bodies | `[2, indefinite [conwayId, ...]]` |
| Reply bodies | `[3, indefinite [conwayGenTx, ...]]` |
| Done | `[4]`, `8104`, only while answering a blocking inventory request |
| Conway ID | `[6, bytes32]`, prefix `82065820` |
| Conway GenTx | `[6, tag24(bytes(original))]`; fixture `8206d8184584a0a0f5f6` |

Protocol inner lists must be indefinite as in the pinned decoder. Numeric
non-shortest encodings are accepted with uint16/uint32 range checks before
narrowing. Outer arities, era, tag and agency are exact. Other eras are unsupported.

Advertised size is `N + 2 + 2 + byteStringHeaderSize(N)`: two bytes for the
hard-fork wrapper, two for tag24, then the definite byte-string header and the
original bytes. It excludes the ReplyTxs/list/mux overhead. This follows
`wrapCBORinCBOROverhead` plus hard-fork `txWireSize` overhead, applied to the
actual unchanged original sent. Native `wireSizeTxF` measures the reference
serializer's output; no equality is claimed for a native reserialization of
arbitrary noncanonical envelopes. Our own advertised size always matches our
actual outgoing wrapper. Boundary examples: 23→28, 24→30, 255→261, 256→263,
65535→65542 and 65536→65545 bytes.

## Accounting and local limits

Inventory acknowledgement removes the oldest outstanding IDs. Blocking requests
must leave zero unacknowledged IDs and request at least one. Nonblocking requests
must leave some unacknowledged IDs and have a nonzero acknowledgement or request.
Replies never exceed the requested count. An offer appears at most once per lease.
Bodies must be outstanding and cannot be requested twice or duplicated in one request.

These are explicit local policy choices:

- Eight originals, 512 KiB total, 65,536 bytes each; at most eight IDs per body request.
- Request counts accept the uint16 domain without allocating from the requested
  count. The actual advertised inventory is bounded to eight. Unlike the native
  configurable `maxUnacked` check, this finite source need not reject a request
  window of ten just because only eight transactions are available.
- Repeated body requests are rejected as described by the protocol type contract;
  the pinned `Outbound.hs` lookup itself does not track a served-ID set.
- 64 requests, 128 retained metadata events, five-second operation/idle waits,
  five-second default handshake (ceiling ten), thirty-second maximum whole lease.
  Whole time includes acquisition, snapshot, callbacks, negotiation and serving;
  trusted resource finalizers must complete promptly.
- 131,072 incoming wire bytes, 256 incoming frames, 128 buffered frames,
  131,086 retained ingress bytes; no general multiprotocol router on this connection.
- 600,000 default outgoing wire bytes (ceiling 1 MiB), 256 outgoing frames.
  These include handshake and mux headers and are reserved before each write.
- Outgoing protocol messages are at most 524,400 bytes and segment into uint16
  SDUs. The new `Mux.segmentProtocolMessage` has a 2,500,000-byte absolute ceiling;
  existing `segmentBounded` keeps its 65,535-byte ceiling. Each caller still supplies
  its stricter protocol ceiling.

Upstream permits 65,535-byte Init/Idle messages and 2,500,000-byte response messages.
Its Init/Idle/blocking waits are unlimited and its other response waits are ten
seconds. The finite time/resource limits above are local policy, not upstream defaults.

The existing single connection owner serializes transport operations and preserves
buffered pipelined requests in order. A decoder/agency/accounting error, EOF,
wrong mux route/direction, deadline or cancellation closes the connection and
unwinds the lease. Physical close is attempted once; a close failure is retained
and surfaced by resource finalization, including after otherwise successful use.
A buffered suffix at terminal completion prevents success.
No partial write is reported as `BodiesWritten`; a completed write still says
nothing about the reference mempool or ledger.

## Offline validation

Focused suites: `network/testOnly lab.network.TxSubmission2Suite lab.network.MuxSuite`
and `networkRuntime/testOnly lab.network.TxSubmission2SessionSuite`.
The tested properties include all golden prefixes, exact original preservation,
size-header boundaries, FIFO/partial acknowledgements, invalid requests, pipelining,
fragmentation, source replacement after snapshot, outgoing caps, bounded acquisition,
stalled reads/writes, cancellation, and exact close/release ownership. A 65,536-byte
original is checked against independently constructed bytes across two SDUs.

No native helper, signing job, Docker workload, live cluster or public publication
was run for this transport implementation. API/pool integration, global lease permits,
reference acceptance, reconnect policy and follower inclusion belong to the integration
owner. They remain necessary before claiming end-to-end ADA submission.

### Recorded verification (2026-10-09 UTC)

Java 21.0.12.1, sbt 1.10.7, Scala 3.3.8; cached dependencies;
`-XX:ActiveProcessorCount=2 -Xmx1200m`. No native reference process was started.

| Check | Result |
| --- | --- |
| network / compile; networkRuntime / compile | passed |
| network and networkRuntime main/test scalafmt | completed; only transport-owned files changed |
| network / test | 110 passed, 0 failed, 0 errors |
| networkRuntime / test | 70 passed, 0 failed, 0 errors |
| New TxSubmission2Suite | 10 passed |
| New TxSubmission2SessionSuite | 17 passed |
| git diff --check | passed |

The module regression run completed at 17:02:19 UTC. It includes the existing local
TCP lifecycle tests; these bind only local scripted peers and are not live Cardano
cluster tests. Test reports reside under each module's ignored `target/test-reports`.
The tracked evidence is this Markdown summary rather than raw logs.

### Indefinite-envelope follow-up (2026-10-09 UTC)

Review identified that the shared signed-transaction checker accepts four-member
indefinite envelopes while the original relay identity extractor required a definite
array. The extractor now consumes exactly four members and the mandatory final break;
transaction and body originals are never normalized. One-lease ownership, accounting,
wire wrappers and byte/depth/time ceilings are unchanged.

Focused verification completed at 17:08:46 UTC:

| Suite | Result |
| --- | --- |
| TxSubmission2Suite | 12 passed |
| TxSubmission2SessionSuite | 18 passed |
| TxSubmission2AdmissionBoundarySuite | 1 passed |

Negative controls cover early/missing/duplicate breaks, fewer or extra members,
trailing bytes, malformed nested members, non-map bodies, non-Boolean validity,
excess nesting and the 65,536-byte boundary. Positive tests include unchanged
noncanonical/indefinite bodies and exact indefinite-envelope wire delivery.

The optional cross-commit regression resides in
`network-runtime/src/it/scala/lab/network/TxSubmission2AdmissionBoundarySuite.scala`.
It uses actual `SignedTransaction.checked` from shared commit
`a60eb662e2f4ac7be9b8f9ad158ccadf5aa24487`; that source has SHA-256
`3448721f920c390a43227e98f94724f1bb3fe0a77354bc369497395818f87cea`.
This is the structural identity boundary, not the full ledger `AdaAdmission` validator.
Network modules intentionally do not depend on ledger or app modules.

On an integration branch containing the shared identity source, run:

```text
set networkRuntime / Test / unmanagedSourceDirectories += (networkRuntime / baseDirectory).value / "src" / "it" / "scala"
networkRuntime/testOnly lab.network.TxSubmission2AdmissionBoundarySuite
```

The isolated transport base predates that shared source. Its verification used an
exact `git show` extraction of the pinned file into ignored
`local-evidence/indefinite-envelope/SignedTransaction.scala`, plus this temporary
sbt setting before the two commands above:

```text
set core / Compile / unmanagedSources += file("local-evidence/indefinite-envelope/SignedTransaction.scala")
```

No build dependency or production source from the shared commit was copied into the
transport commit. Do not add the extra source setting when the shared class already
exists on the integration branch. No live/native jobs were run for this fix.

## Reference initialization delay

The isolated reference profile explicitly sets `TxSubmissionInitDelay: 0`.
The pinned network implementation otherwise waits 60 seconds after protocol
initialization before requesting transaction IDs. That default exceeds this
publisher's five-second operation and thirty-second lease bounds; repeated
connections restart the delay. Two retained isolated attempts without the
override admitted through HTTP but did not reach an announcement or inclusion.

This is a supported node 11.1.3 setting, parsed by
[POM.hs](https://github.com/IntersectMBO/cardano-node/blob/11.1.3/cardano-node/src/Cardano/Node/Configuration/POM.hs#L387-L390)
and passed to diffusion by
[Run.hs](https://github.com/IntersectMBO/cardano-node/blob/11.1.3/cardano-node/src/Cardano/Node/Run.hs#L384).
The controller preserves the original configuration and records before/after
hashes and the sole changed field. It changes neither wire encoding nor ledger
rules. Success under this explicit local profile does not prove interoperability
with the reference default delay. Supporting that default requires a separately
bounded pre-lease startup design; the current lease limit is not extended.
