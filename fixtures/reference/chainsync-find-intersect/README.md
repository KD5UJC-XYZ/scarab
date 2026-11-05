# ChainSync codec and intersection selection

Status: Current. Executed evidence for [#755](https://github.com/KD5UJC-XYZ/scarab/issues/755).

The native reference's FindIntersect decoder reads a definite or indefinite list without a candidate
count policy (`ChainSync/Codec.hs:165-166,209-214`, network `2fb69829`). Its encoder writes all supplied
points. The NtN receive driver applies the 65535-byte `smallByteLimit`; it is not an encoder limit or
an NtC limit. Consensus's NtC server uses plain `runPeer`, without that budget.

A Cardano point is `[]` or `[slot, hash]`, with a Word64 slot and exactly 32 definite hash bytes.
The observer executes the released codec and driver, using the unchanged consensus raw-hash decoder
at the Cardano hash size; its [build and invocation](../../../scripts/oracle/chainsync-find-intersect/README.md)
and `provenance.json` identify the sources, dependencies and executed artifacts.

## Wire observations

On 2026-11-05, `cases.jsonl` records 58 executed observations: 22 accepted, 28 refused and eight
incremental inputs needing more bytes. Large messages remain compact recipes: `prefix`, `count`
copies of `unit`, then `suffix`. Each input carries its byte count and SHA-256; accepted outputs carry
the native encoded byte count and SHA-256, and points carry their count and first value.

The corpus includes:

- the original NtN-sized cases: empty/origin points, 64/65/1724 ordinary points, 9000/65000 origins,
  slot bounds, definite/indefinite list framing, hash lengths/chunks and malformed point structures;
- NtC lists of 65536/70000 origins and 70000 ordinary points (2,660,007 bytes definite or 2,660,004
  indefinite), plus malformed structures and a bad hash after 70000 origins;
- direct native outgoing encoding of 65, 1025 and 70000 ordinary points and 65536 origins;
- a generic opaque point whose valid 65535-byte definite-list request encodes to 65536 bytes when
  the native encoder uses indefinite-list framing;
- a 2,600,000-byte opaque serialized block transferred in a 2,600,012-byte NtC RollForward, with
  the original block contents and full wire encoding checked separately;
- serialized-block prefixes with wrong tags, indefinite byte strings or the wrong payload major type,
  rejected before missing contents are requested; genuine incomplete tags/payloads/tips remain partial,
  and a complete empty serialized block is accepted;
- declared list/hash/payload lengths at 2^31, 2^63 and 2^64-1. The 2^31 incomplete inputs wait;
  an available malformed first point fails before the rest of the declared list arrives.
  Declarations beyond native signed Int64 capacity fail immediately. A declared 65536-byte hash
  waits for missing contents, then refuses a complete value of that size.

`ChainSyncFindIntersectReferenceSuite` checks those observed verdicts, complete consumption for
accepted messages, native point summaries, and exact SHA-256 identities of encoded bytes. The generic
opaque-point case uses its own byte-string point codec; it is not a Cardano hash case.

## Selection observations

`selection.jsonl` contains 16 native `AF.selectPoints` observations and one offsets-only observation.
The generator records fragment lengths 0, 1, 2, 3, 4, 5, 8, 13, 16, 21, 34, 64 and 2160, the k=5/length=9
case, and block-anchor cases. Blocks have increasing slots and block numbers with a repeated
32-byte hash; selection returns native point values. Both empty anchored fragments return the anchor
twice because `mkOffsets` returns `[0,0]`; the native selector preserves duplicate offsets.
The Int.MaxValue observation executes the original floating Fibonacci function across the entire
offset range needed by an Int-indexed fragment without allocating that fragment.

## Scope

These finite cases establish the recorded codec, byte-preservation and selection observations.
They do not establish a valid ledger block, live-chain consensus agreement, arbitrary-size JVM
allocation capability, or NtN limited-driver behavior. The block contents are intentionally opaque.
Selection of a common point from a peer's offered list is covered by runtime tests separately.
Native full source files are not copied into this fixture; provenance identifies the exact source
blobs, excerpt locations and executed image. Bulk input expansions and build artifacts are not published.
