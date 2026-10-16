# 0013. CBOR documents are an index over the input

- **Status**: Accepted
- **Date**: 2026-10-16
- **Issue**: #94

## Context

[ADR 0005](0005-cbor-codec-and-scodec.md) decided that CBOR is decoded into "our own tree that keeps
each item's original bytes". The tree was one `Node`, one `Bytes` window and one `Value` per item.
Measured on the Haskell section of a preprod epoch-188 snapshot (217 MiB, 27.3 million items), it
retained 2.85 GB, 104 bytes an item and 13 times the encoded size, and it was 77% of what a read of
the snapshot needed: the node could not read the file in 3 GiB, nor write it, since the write
reads the file back to check it ([the design](../design/memory-lean-decoding.md), section 2).

The Haskell node has no such tree. A decoder there reads the input once, and the original bytes of
an item are a `ByteSpan` of two offsets, `slice` of the shared input (`Cardano.Ledger.Binary`,
`Decoding/Annotated.hs:69-79,112-114,227-231`). The decision of ADR 0005 that matters (original
bytes kept, no recursion on the stack, typed located errors, one runtime for the generated and the
hand-written decoders) does not depend on the tree being made of objects.

## Decision

`Cbor.decode` checks the input as before (limits, UTF-8, trailing bytes, the arity of maps) and
writes an index: two ints an item, where its head starts and where the item ends, numbered in
the order the items begin, in blocks that are never copied as the index grows. It builds no object
per item. A `Node` is a 24-byte view of one item of the index; the tree is no longer a data
structure but what `Node.value` shows of it:

- `value` is a `def`, never cached: it reads one level from the input (a container's children are
  views again), so a document costs its index and a decoder that walks a large collection holds
  the items it is at. Byte strings and text are copied out, as before, so that a value kept in a
  decoded state does not pin the input.
- `original` is a window of the input (`Bytes.wrap(data, start, end - start)`), the `ByteSpan`
  of Haskell. Anything hashed or signed is still hashed over the bytes as received, including
  indefinite lengths and non-minimal heads.
- `offset` gives the start of an item without making a window, for locating an error. `entries`
  gives the pairs of a map one at a time, without the vector `value` makes of all of them.
- `Node.apply(value, original)` still builds a node by hand (the Leios and Handshake encoders and the
  tests); `Value`, `Codec`, `rt.Arr`, `rt.Maps` and the generated models are unchanged.

The builder is the explicit-stack machine of ADR 0005 and ADR 0011; the index finds the next sibling
of an item by a galloping search over the sorted starts, so the walks stay iterative and need no
more than a few ints of stack. The old tree builder stays in the test tree (`OracleCbor`), and a
differential suite checks that the new one gives the same value, the same original bytes, the same
offsets and the same error, message for message, on the fixtures, mutations of them, random items of
every wire form and nesting of 90,000 levels.

This supersedes the sentence of ADR 0005 that says the decode builds "our own tree"; the rest of
ADR 0005 (scodec only for read-once fixed layouts, hand-written per-block paths, the dependency
rationale) stands.

## Consequences

- The retained size of a document is 7.7 bytes an item (209 MiB for the 27.3 million items above, in
  place of 2.85 GB), and building it takes a seventh of the time.
- A decoder that reads `node.value` twice parses the item twice. Decoders read it once and keep the
  result; `Alternatives` and `maybeTagged` read a few small items twice. A cache would bring the
  tree back, so there is none.
- An input is limited to 2 GiB (32-bit offsets), which `Limits.maxInputBytes: Int` already was.
- A node keeps the whole input array alive, as every `original` window already did.
- Revisit it if the index of a section no longer fits (a mainnet-scale state of several GiB), or if
  the parse in `value` shows in a profile of the per-block path: the next step is then a pull reader
  under `Codec` (option 3 of the design), for which the index is the cursor.
