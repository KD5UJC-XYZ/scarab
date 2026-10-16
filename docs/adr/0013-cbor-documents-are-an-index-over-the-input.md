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

## Addendum: collections fold into builders and equal values are shared (stages 2 and 3, 2026-10-16)

With the index in place the typed state, 907 MiB on a snapshot of the epoch-188 shape, was the whole of
what a read needed. Two changes, both in `codec` and the decoders of `storage`; no persisted format changed.

- **`Node.items`** is to an array what `Node.entries` is to a map: its items one at a time, found in the index.
  `Wire.setOf`, `mapOf` and `seqOf` decode them straight into a `SortedSet`, `SortedMap` or `Vector` builder (local to
  the call, not escaping it) and count what they read, so that the error places (`[i]`, `key[i]`, `value[i]`) and the
  number of repeated elements of version 9 are those of the code they replace. A set no longer reads `n.value` to
  look for the tag 258 of an array of a million: it asks for the items first. `Elements.decodeAll` remains for the
  telescope's few past states and the combinators of `models/runtime` remain for blocks, which are small.
- **`Node.share(kind)(a)`** returns the first value equal to `a` that the decode of this node's document has produced
  for that `kind`, or `a`. It is Haskell's `Interns` (`Cardano.Ledger.Binary.Decoding.Sharing`, `Sharing.hs:53-66,111-135`
  of `cardano-ledger-binary` 1.9.1.0, as the design document cites it), which decodes a key against a table of those
  already decoded. A kind (`Interned[A]`) is a value declared by the codec that uses it, and is the key of one table
  in the document's `Interns`, so the type of what a table holds is the type of its kind. `Codec.interned(kind)` (an
  extension in `Wire`) makes a codec share; `Wire.hash` does, for every hash of the state, and the node's
  `Credential` does as a whole (not also its hash: a second lookup costs as much as the first and saves nothing).
  A node built by hand shares nothing.

**Deviation from the design document**, which passes an `Interner` to the codecs with `using`: the codecs are `given`s
of one singleton, each built once and shared by every decode, so a table in them would be a global that grows for
the life of the process, and a `using` parameter would reach every codec on the path from the file to a credential (a
change of every signature on the way, with the interner threaded through each combinator). The node being decoded
already is the environment of a decode (an `Ask`, if one likes), and the table belongs to its `Document`, so a
decode shares and nothing else does. A decode that wants no sharing hands the decoder nodes it built by hand
(`Node.apply`); `SharingSuite` decodes the same bytes both ways and finds the same state.

The table is open-addressed (`Interns.Table`): the values are its slots, 6 to 12 bytes a value, and a lookup reads a
slot and the value in it. A `java.util.HashMap` took 45 bytes a value and raised the heap that a million distinct
voters need from 218 to 267 MiB; this one lowers it to 202 MiB.

Consequences:

- The typed state of the epoch-188 shape is 353 MiB, was 907 MiB (the 6.5 M `Credential.Key` are 650 k), and the
  node runs on that in the epochs the votes stay in the proposals. Figures: `docs/performance.md`, section 12.
- The decode takes about a third longer on that snapshot (a lookup, with its cache misses, for each of 6.5 M credentials)
  and 15% on a normal one.
- What is not shared: the vote maps themselves (the red-black node of an entry, 32 bytes, is 210 MiB of the 353) and
  the `Vote` (a singleton). The pulser's copy of the proposals is equal to them at an epoch boundary; sharing a whole
  map by equality would drop that copy and costs a hash of the map. Not done: it is only worth it if the real states show
  the copies equal, which the file measured (equal by construction, section 12) does not tell.
- `Credential`'s `Ordering` compares `(ordinal, hex)`, a string made per comparison: the sorted maps of credentials
  pay for it at every insertion. Not changed here.
