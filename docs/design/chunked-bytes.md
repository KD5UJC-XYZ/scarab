# Chunked bytes and large local payloads

**Status:** Codec foundation implemented; consumer migration and local capacity parity incomplete.
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

The foundation does not yet migrate CBOR spans, `ProtocolWire`, local serialized block adapters,
receive buffers, hashing, builders or stores. Those consumers must preserve original bytes while
using chunk traversal and checked global positions. Fixed hashes, individual mux SDUs and messages
received under the reference's bounded NtN policy can retain contiguous representations. Large
local payloads must not inherit those bounds accidentally. Integration and native comparisons stay
incomplete until the relevant consumers execute through the wider representation.

No wire or persisted bytes change here. An in-memory change alone does not imply a format bump;
an actual persisted-format change still follows [ADR 0006](../adr/0006-versioned-formats-migrate-on-open.md).
