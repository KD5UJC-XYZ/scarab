# Chunked bytes and large local payloads

**Status:** Codec foundation and local ChainSync serialized-byte integration implemented; ledger and storage migration incomplete.
**Issue:** [#749](https://github.com/KD5UJC-XYZ/scarab/issues/749).

`ChunkedBytes` is an immutable sequence of nonempty `Bytes` chunks with a checked signed-64-bit
total length. Concatenation shares its inputs through `cats.data.Chain`; a slice shares bounded
windows of the original chunks. Global positions, lengths and ranges use `Long`; positions within
an individual array use `Int`. Constructors remove empty chunks, concatenation checks overflow,
and range and cursor operations return closed typed errors for invalid requests. A cursor is a
persistent value: reading it produces a new cursor, and a successful chunk read always advances.
Content equality, unsigned lexicographic order and the polynomial hash do not depend on chunk
boundaries. Traversals use library folds or compiler-checked tail recursion.

The existing `Bytes` representation and API remain unchanged. A hash or key does not need a
chunk sequence, and the UTxO set contains millions of small byte values. Keeping those values in
their existing compact array windows avoids adding per-value chunk metadata; this is a layout
rationale, not a new measured performance claim. The foundation introduces no imperative exception.

`toBytes(maxBytes)` is the explicit boundary to contiguous storage. It checks the caller's bound
before materializing and can share a single existing chunk. Passing the size check does not promise
that an allocation will fit in memory. There is no implicit flattening, protocol policy or universal
message limit in `ChunkedBytes`. Callers retain the reference's limits at the applicable boundary.
Chunk traversal and seeking are linear in the chunks visited; content comparisons and hashes still
inspect the relevant bytes. A wide logical length does not make those operations constant time.

## Reference correspondence

The inspected oracle runtime uses x86-64 GHC 9.10.3 and `bytestring-0.12.2.0`. Its strict
[`ByteString`](https://github.com/haskell/bytestring/blob/0.12.2.0/Data/ByteString/Internal/Type.hs#L283-L307)
holds a native pointer and machine `Int` length; its
[`mallocByteString`](https://github.com/haskell/bytestring/blob/0.12.2.0/Data/ByteString/Internal/Type.hs#L779-L783)
accepts that `Int`. The lazy representation is a sequence of strict chunks, with
[`Int64` length](https://github.com/haskell/bytestring/blob/0.12.2.0/Data/ByteString/Lazy.hs#L311)
and [checked flattening](https://github.com/haskell/bytestring/blob/0.12.2.0/Data/ByteString/Lazy/Internal.hs#L324-L350).
On this platform those lengths are wider than JVM array indices. A `Long` field around one JVM
array would not remove its capacity boundary; a chunk sequence supplies the wider representation.

At the pinned network commit `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`,
[`Serialised`](https://github.com/IntersectMBO/ouroboros-network/blob/2fb69829a19b5c04e4fb212bdd78fb63f9d2d776/ouroboros-network/api/lib/Ouroboros/Network/Block.hs#L438-L504)
wraps lazy bytes, although its CBOR instance flattens through strict bytes. The native local
ChainSync server uses
[`runPeer`](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/ouroboros-consensus-diffusion/src/ouroboros-consensus-diffusion/Ouroboros/Consensus/Network/NodeToClient.hs#L438-L453)
without the NtN codec byte budget. These source facts motivate a representation that does not
introduce a signed-32-bit local payload limit. They do not prove that a complete ledger-valid block
larger than 2 GiB has been forged, validated or stored.

## Verification and remaining boundaries

`ChunkedBytesSuite` compares different chunkings with a contiguous model, checks concatenation and
slice laws, cursor progress and immutability, checked overflow, and traversals on a 256 KiB stack.
Its wide-offset case shares one 64 KiB array across enough chunks to cross 2 GiB; it is an executed
logical-offset test, not an allocation or native full-block witness.

Local ChainSync uses `CardanoBlockFetch.LocalBlock` with `ChunkedBytes` payloads. The primary
`ChainSync.encodeChunked` and engine entrypoints build a logical message without flattening its
payload or offered-point collection. `FindIntersect` and client APIs carry `Chain[Point]`; Vector
conveniences only widen an existing bounded collection into a Chain. The payload remains one
definite CBOR byte string under tag 24: internal chunks do not appear on the wire.

`MuxChannel.sendChunked` admits that complete logical message atomically. Its writer packs across
internal chunk boundaries into bounded SDUs, preserves per-lane FIFO and turn-taking, and releases
the soft egress budget as SDUs are written. The held-byte accounting uses `BigInt` so two admitted
logical messages cannot overflow a signed-64-bit sum. Only one SDU is materialized at a time.

Ordinary and rolling local clients use `ChainSync.responseDecoder`/the shared engine decoder through
`recvStreaming`. The payload continuation retains immutable input chunks, while point hashes and
scalar fields remain bounded. Native wrapper checks retain their order: a wrong tag or indefinite
byte-string head is refused before waiting for a body; an invalid point hash is refused after its
complete declared body. Receivers preserve coalesced trailing messages and retire a cancelled
partially consumed channel. The bounded NtN ChainSync and BlockFetch receive policies remain at
their reference boundaries.

The existing `encode` and `decodePrefix(Bytes)` methods are contiguous conveniences. Their input
or output must fit `Bytes`; they do not establish unlimited capacity. Generic payload adapters that
use `StreamingDecoder.bounded` retain their own contiguous representation contract. Explicit
conversions preserve a typed `ChunkedBytes.Error` rather than presenting capacity as a protocol rule.

`ChunkedChainSyncSuite` checks the native opaque serialized-byte case above 2 GiB, including the
incremental payload/wire hashes, fragmented wrapper refusals and a small-stack continuation.
`ChunkedChainSyncClientSuite` exercises both ordinary and rolling clients with a receive array
smaller than the payload. Egress tests cover internal chunk boundaries and accounting beyond a
`Long` sum; the `chainsync-ntc-response-streamed` fuzz target compares finite fragmented inputs
against the independently implemented buffered decoder, including values, errors and first-message
consumption. These tests concern opaque serialization, not ledger-valid giant blocks.

Ledger CBOR spans, complete block/transaction decoding and validation, hash/signature callers,
builders and stores still use their existing bounded representations. The production local server
wraps the bytes supplied by its validated storage source; it does not make that source capable of
validating or storing a multi-gigabyte block. Those consumers require a separate migration that
preserves original bytes while using checked global positions. Fixed hashes, individual mux SDUs
and messages received under the reference's bounded NtN policy retain contiguous representations.

No wire or persisted bytes change here. An in-memory change alone does not imply a format bump;
an actual persisted-format change still follows [ADR 0006](../adr/0006-versioned-formats-migrate-on-open.md).
