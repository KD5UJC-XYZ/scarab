# Handshake/mux source conformance and local transport simulation

This is a bounded research milestone, not a Cardano peer or a general multiplexer. Its transport implements protocol 0 only. The separate [pure ChainSync milestone](chain-sync.md) adds envelope/state/model checks, not a transport session. No external endpoints, chain download, transaction submission, ledger validation, keys, discovery, or persistent networking are exposed.

## Frozen scope and evidence

- Reference node: cardano-node 11.1.3, `938cba990357ae7c4b7f95c8f75dd9d31174bbeb`.
- Wire source: ouroboros-network `c45735a56c567fa977969173d18943bac6bb3821`.
- Independent manual golden source: scalus-node `79a056d0db66eae034d0aa0120f4566bce7d298b`.
- [Fixture manifest](network-fixtures.json): ten literal byte vectors, semantic values, source paths, derivation classes, SHA-256 of decoded bytes and oracle status.
- Three Scalus literals were already hand-crafted upstream from CDDL. The other seven are source-derived/manual expressions, not Haskell-emitted fixtures. No Haskell exporter, reference node, or actual-node interoperability was run.
- Original codec/runtime implementations are Scala ports of the documented wire behavior, not imports of Scalus's Future-based networking runtime. Original upstream licenses and applicable notices are retained in `fixtures/licenses`.

NtN recognizes precisely logical versions 14, 15 and experimental 16. Default proposals offer only 14, initiator-only, no peer sharing, query or Peras. Offline encoding of 15/16 does not implement SRV or Peras. NtC recognizes precisely 16–23; every wire key sets `0x8000`, on mainnet and testnets alike. NtC query requests version information and terminates the handshake; it is not LocalStateQuery. Historical ledger eras are independent of these transport versions.

## Module and runtime boundary

`network` depends on `core` only, not `vm` or Cats Effect. Pure ADTs and `Either` results expose codecs and negotiation transitions. A separate bounded generic handshake CBOR parser preserves unknown-term skip semantics without widening the transaction decoder. `Mux.Decoder` retains partial frames immutably; handshake `decodePrefix` returns leftovers.

The `app` layer owns `ByteTransport[F]`, `HandshakeSession[F]`, bounded `Loopback` queues and `TcpLoopback` resources. The same session works through either adapter, so a later sidecar can implement the byte-stream boundary without moving protocol state into a foreign runtime. No sidecar is implemented. The TCP adapter uses JDK asynchronous channels, binds only IPv4 `127.0.0.1` on an ephemeral port, and has no public-peer address option. The in-memory adapter is explicitly a simulation and closes both directions immediately, dropping queued data; TCP supplies real EOF semantics.

Session reads are serialized, accept only protocol 0 and the opposite direction, and preserve pending CBOR and mux bytes. Every phase has a whole-operation monotonic Cats Effect timeout, not a timeout reset by each fragment. Remote mux timestamps are opaque wrapping unsigned values and never drive deadlines. Error, timeout, cancellation and resource release close the owned stream. TCP cancellation closes the in-flight operation's channel; resources separately own listener and both sockets. Queues and reads are bounded, and there is no `unsafeRunSync` or competing application runtime.

## Wire rules and deliberately stricter local policy

The mux header is eight big-endian bytes. Direction bit clear means initiator; set means responder, following executable upstream code even though its prose comment is reversed. Zero-length SDUs are rejected. The wire capacity is 65535 bytes, not 12288. Inbound frames can use that full capacity with explicit local limits; the handshake session deliberately caps an individual SDU and total pending handshake bytes at 5760.

Handshake cap: 5760 bytes; default phase deadline: 10 seconds. Local generic-term policy: depth 24, 512 terms, 4096 string bytes, 1024 refusal-text bytes. Definite message arrays and version maps are required. Recognized map keys must be strictly increasing; unknown entries are bounded generic terms and skipped without silently sorting or collapsing duplicates. Unknown keys need not be in ascending order. Encoders produce ascending distinct known versions. Refusal array arity is checked strictly; the pinned reference decoder reads but does not enforce that length, so this is a deliberate narrower acceptance policy.

Negotiation selects the highest common version and fails on malformed/incompatible data at that version, never silently downgrading. Client acceptance must name an offered version and match magic; feature agreement follows pinned semantics. Tag-0 simultaneous-open replies are handled as negotiation. Query results and refusal are terminal outcomes distinct from an application session. The pure client cannot be reused after Done.

Additional resource limits are implementation policy, not Cardano consensus/wire limits. Mux feed defaults bound each input chunk to 1 MiB and output batch to 4096 frames; partial state is bounded by one configured SDU. The app TCP/Queue adapters default to 1024-byte chunks; Queue capacity defaults to four. The session's total pending limit can reject a coalesced batch of otherwise separately valid messages above 5760 bytes. Only one handshake exchange is exposed by the demo.

## Run and acceptance boundary

```sh
./scripts/sbtw check
./scripts/sbtw 'app/run network-demo'
# Equivalent alias:
./scripts/sbtw 'app/run network-selftest'
```

The demo performs four independently scripted TCP-loopback exchanges: NtN14 preview; NtC16 preview and mainnet; NtC23 version query. Literal expected request and response bytes are not calculated by the implementation under test. Responses are sent in two-byte chunks; TCP is free to coalesce them. Separate deterministic tests cover every byte split, coalesced messages, multi-SDU CBOR, malformed/truncated/oversized data, unoffered versions, wrong magic, illegal state, timeouts, cancellation and resource release. A self-written transport exchange does not establish interoperability.

Next stronger gate: run a pinned Haskell exporter using the actual version/handshake/mux codecs, retain its build plan and executable hash, and compare emitted fixtures. A separate authorized local reference-node query is another future gate. None of these claims includes adversarial-network readiness, a full multiplexer, Unix-domain NtC socket integration, historical block conformance or production-node behavior.
