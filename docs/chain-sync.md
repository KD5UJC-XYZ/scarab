# Pure ChainSync envelope/state conformance and deterministic fixture model

Version 0.10.0 deliberately splits the research recommendation into a **pure milestone**.
The CLI performs bounded offline checks; it does not start a ChainSync connection,
listener, peer, in-memory transport or reference process. The existing handshake-only
CE transport is unchanged. A connection-owned handshake/application phase handoff,
shared mux leftovers, cancellation and deadlines are the next separate milestone.

## Evidence and pins

- cardano-node 11.1.3: `938cba990357ae7c4b7f95c8f75dd9d31174bbeb` (not executed).
- ouroboros-network: `c45735a56c567fa977969173d18943bac6bb3821`.
- ouroboros-consensus: `82ecba329d7d054340bf707d44fe6e9ac27cec40`.
- scalus-node: `79a056d0db66eae034d0aa0120f4566bce7d298b`.
- [Envelope manifest](../fixtures/chain-sync/envelope-manifest.json): exact manual/source-derived
  bytes, state, sender, next state, SHA-256 and immutable source links/checksums.
- [Payload manifest](../fixtures/chain-sync/payload-manifest.json): preserved binary bytes,
  size, SHA-256, pinned Git blob IDs and original upstream paths/URLs. Source acquisition
  verified each against the pinned consensus tree; portable checks recheck the retained bytes.

Evidence classes remain separate:

1. RequestNext `8100`, AwaitReply `8101`, Done `8107`, Origin point `80` and Origin tip
   `828000` are upstream **manual golden assertions**, not fresh Haskell captures.
2. Intersection/rollback envelopes are **source-derived** from pinned Haskell encoder
   expressions. The definite nonempty candidate-list example is an accepted decoder
   form, not the reference encoder's preferred output.
3. NtN `Header_Conway` (856 bytes) and NtC12 `Block_Conway` (7807 bytes) are upstream
   **typed serialization examples**. They do not prove accepted-chain membership,
   valid KES/VRF, ledger validity or whole-protocol interoperability.
4. `SerialisedHeader_Conway` (13 bytes) and `SerialisedBlock_Conway` (18 bytes) are genuine
   upstream **serialization-envelope goldens with ASCII placeholders** `<HEADER>` and
   `<CARDANO_BLOCK>`. Their interiors are not header/block CBOR. Adapter acceptance
   intentionally does not turn them into validated payloads.
5. CLI RollForward bytes are **source-derived envelope / upstream payload** plus a
   **synthetic Origin tip**. These are not coherent chain transcripts. Generic test payload
   `1800` is deliberately nonminimal synthetic CBOR, not a Cardano header.

All copied payloads retain upstream Apache-2.0 LICENSE and NOTICE under
`fixtures/chain-sync`. Existing network/Scalus-node attribution remains in
`fixtures/licenses`. This is independently written Scala code based on wire behavior;
no upstream networking/consensus runtime implementation is vendored.

## API and wire contract

`network` remains pure and depends only on `core`. `ChainSync` provides checked UInt64,
Point, normalized Tip, state/role/message ADTs, stateful encode and decodePrefix, and
an explicit transition function. UInt64 construction rejects outside 0..2^64−1,
including values above signed Long. Final private classes avoid a case-class copy
escape. Tip Origin always has block number zero; decoding `[[],1]` consumes the integer
and normalizes it to Origin, matching pinned `decodeTip` rather than inventing a rejection.

| Sender | State | Message | Next |
|---|---|---|---|
| Client | Idle | RequestNext | NextCanAwait |
| Server | NextCanAwait | AwaitReply | NextMustReply |
| Server | Either Next | RollForward / RollBackward | Idle |
| Client | Idle | FindIntersect | Intersect |
| Server | Intersect | IntersectFound / IntersectNotFound | Idle |
| Client | Idle | Done | Done |

All outer arrays have definite exact arity. A repeated AwaitReply, unsolicited server
update, client request while waiting, wrong sender or Done outside client Idle fails.
Done is terminal and has no acknowledgment. Encoding checks agency/state too.
FindIntersect encodes empty points as `80` and nonempty points as `9f ... ff`;
decoding accepts definite and indefinite candidate lists. Points and tips use definite
exact arrays. Valid nonminimal unsigned arguments remain accepted. Malformed reserved,
overlong/out-of-range, invalid UTF-8 or unexpected-break forms fail.

`DecodeResult` is NeedMore, Failed, or Decoded(value, consumedBytes). A complete first
message may precede an arbitrarily large caller-owned suffix: only the consumed item is
bounded/copied. Callers own their pending-buffer limit and must enforce it before retention.
The decoder is a bounded pure prefix parser, not a state-owning incremental session.
On EOF a caller must treat NeedMore as truncation, not success. No implicit retry or
poisoned-stream recovery is supplied here.

`ChainSyncFixtures` exposes distinct private-constructor `OpaqueNtNHeaderFixture`
and `OpaqueNtCBlockFixture`. NtN recognizes only the definite `[6,tag24(bstr)]` Conway
header serialization envelope. NtC recognizes definite `tag24(bstr)` full-block envelopes;
its **opaque interior is not era-checked**, including the upstream placeholder. The pinned
retained typed-block fixture is Conway, and the CLI admits only its expected checksum.
The inner disk-format Conway era tag is 7, not NtN header era index 6. Indefinite
byte-string wrappers and indefinite NtN outer arrays are rejected like the narrow source
codec. Both adapters preserve exact original outer and inner bytes and do not parse the
inner Cardano object. Generic payload framing supports bounded arbitrary CBOR, including
floats/simple values, without widening the separate transaction CBOR decoder.

Profiles label retained fixture choices only: NtN14 / protocol2 /
CardanoNodeToNodeVersion2 and NtC16 / protocol5 / CardanoNodeToClientVersion12.
No handshake-to-ChainSync integration or mux application registration exists yet.
Network versions and block-codec versions are different namespaces.

## Local bounds and model semantics

Default message bound is 65535 bytes, matching pinned ChainSync smallByteLimit. It is
separate from a mux SDU capacity and handshake's 5760-byte limit. Local limits are depth24,
8192 items (string chunks included), 65535 string bytes, 64 candidates and 64 hash bytes.
Configuration itself has checked upper bounds; claimed sizes are checked before allocation.
The Cardano fixture point adapter and synthetic model require 32-byte hashes; generic
wire Point permits other bounded byte-string hash widths. Item/depth/byte bounds apply
on both encoding and decoding, including standalone point/tip APIs. Retained large
payloads are bounded examples, not support for arbitrary real NtC block sizes.

`FixtureChainModel` bounds history to 256 and trace to 1024. It selects the first candidate
present in server history **in client preference order**, with slot AND hash equality.
Origin succeeds only if offered and present. An empty list finds nothing. An unoffered
IntersectFound is a semantic model error; the wire codec alone cannot verify that history.
A failed intersection preserves the cursor/history. A successful offered new anchor may
replace the retained cursor; the server may next roll back to that anchor.

Forward events require an explicit distinct synthetic point; payload bytes never supply
or authenticate that point. Unknown/pruned rollback targets require reintersection,
never silently reset genesis. Bounds fail explicitly instead of unbounded retention.
The finite script intersects B on A→B→C, rolls back to B, forwards C, awaits, rolls back
to A, forwards fork D→E (same slots, different hashes), then sends Done. Trace and final
history are asserted exactly. This is **protocol follower cursor rollback only**:
no UTxO, certificate, nonce, stake, mempool or ledger rollback is implemented.

## Run and next gate

```sh
./scripts/sbtw check
./scripts/sbtw 'app/run chain-sync-selftest'
./scripts/sbtw app/runtimeClasspathFile
python3 scripts/verify-chain-sync-cli.py
```

Tests cover all legal/illegal agency-state combinations, literal bytes, every proper
prefix of short messages and all four retained payload envelopes, a seeded large-payload
fragment sequence, huge suffix preservation, UInt64 boundaries, malformed/resource attacks,
opaque-family separation, exact fixture hashes and deterministic fork/intersection behavior.
The CLI rejects extra arguments/endpoint options and reports transportExchanges=0 and
false flags for reference runtime, Cardano header/block validation, ledger rollback and
live peer checks. It does not claim a byte-transport peer simulation.

Deferred: CE connection/session handoff with one owner and shared leftovers; bounded
single-active-protocol registration; monotonic state deadlines; fragmentation across
SDUs; coalesced handshake/application frames; cancellation/EOF/backpressure and terminal
resource release. Also deferred: a pinned Haskell-exported whole transcript, live peers,
pipelining, BlockFetch, KeepAlive, historical per-era payload decoding, KES/opcert/leader
validation, consensus selection, ledger replay/rollback, storage and signing. No native
runtime, private key, listener, reference socket retry or publication was added.
