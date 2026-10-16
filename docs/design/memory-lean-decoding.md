# Memory-lean decoding

**Status**: Stages 0 and 1 are implemented and measured (section 8); stages 2 to 4 are proposals.
The proposal was written from spike measurements of 2026-10-16 (a spike in the sense of
[docs/process.md](../process.md)); the decision is [ADR 0013](../adr/0013-cbor-documents-are-an-index-over-the-input.md).
Tracked in [#94](https://github.com/KD5UJC-XYZ/scarab/issues/94). The decision it changes is
[ADR 0005](../adr/0005-cbor-codec-and-scodec.md) (the byte-preserving tree); the reference is
[ADR 0001](../adr/0001-haskell-node-as-reference.md).

## 1. Summary

- Every typed decode first builds a generic tree: one `Node`, one `Bytes` window and one `Value` per
  CBOR item. On the Haskell section of a preprod epoch-188 snapshot (217 MiB, 27.3 M items) the tree
  alone is **2.85 GB retained, 104 bytes an item, 13 times the encoded size**. The typed state that the
  decoder finally returns is 0.84 GB. The tree is therefore 77% of what a read needs, and it is the
  part every option below removes; the typed state stays under every option.
- **Recommendation**: keep `Node` and `Value` as the API of the 700 decoders, and change what a
  `Node` *is*: a 24-byte view onto an index (two ints an item, 8 to 10 bytes) over the shared input
  array, with `value` and `original` computed on demand (option 1, which is Haskell's `ByteSpan`).
  Then fold the children of large collections straight into the result (option 2, a small step on
  top of 1), then share the repeated credentials as Haskell's `Interns` do (a further 0.5 GB of
  typed state). A pull reader under `Codec` (option 3) is the most Haskell-like shape and the most
  expensive; it is not needed to meet #94 and is kept as a later step.
- Estimated read-back peak at epoch 188 after stage 1: about **2.3 GB** (measured parts plus
  estimates, section 6), under the 3 GiB heap with 0.7 GB to spare; after the interning stage about
  1.8 GB.

## 2. What was measured

All runs in Docker (`scarab-jdk:local`, JDK 21, G1), `-Xmx` as stated, by the spike
`node/src/test/scala/xyz/kd5ujc/scarab/node/Memory94Main.scala` (marked SPIKE; not part of the node).
"Retained" is the heap in use after two full collections while the result is still reachable, less
what was in use before the stage began (the 222 MiB file content). "Min `-Xmx`" is a bisection to
64 MiB; it includes the content array and the headroom G1 needs.

**The input is not the file the task named.** `snapshot-00000000000079577924-3026442.bin` had been
rotated out of the live node's `db/snapshots` by the time the runs began. The measured file is
`snapshot-00000000000080873966-3073752.bin` (222 MiB, Haskell section 217 MiB, 27,315,775 CBOR items),
a copy of which was read from a read-only mount; nothing under `.workspace/data` was changed. It
is comparable: the five 648,752-entry votes maps stay in the proposals until epochs 191 and 193, so
a snapshot from a later point of the same epoch range holds the same data, and the issue's own file
was 233 MB (the Haskell section 227 MB) against 222 MiB here.

| Stage | Retained after GC | Min `-Xmx` | Time |
|---|---|---|---|
| `Cbor.decode` of the Haskell section (the tree) | 2,852 MiB (104 B an item, 13.1x the section) | 3,093 MiB | 1.8 s |
| `HaskellLedgerFile.decode` (typed result) | 842 MiB | 3,406 MiB | 6.5 s |
| `SnapshotV4` read to `NodeState` | 867 MiB | 3,449 MiB | 9.6 s |
| `SnapshotV4` write with its read-back (the issue's path), live state held | sampled live peak 4,527 MiB; unsampled post-GC peak 5,095 MiB at 24 GiB | not bisected (a bisection of this stage includes the 3.4 GiB decode that builds its input) | 28 s |
| Option 1 spike: index with two ints per item | 259 MiB (9.8 B an item; 8 B ideal, the rest is power-of-two slack) | 552 MiB | 0.37 s |
| Synthetic votes map, 1 M entries, 31 MiB of CBOR, today's `Wire.mapOf` | typed result 123 MiB | 587 MiB | 1.0 s |

The 3 GiB heap of the issue cannot even *read* this file today (min 3.4 GiB), before the write path
adds its own copies.

**Where the write-path peak comes from** (4.5 GB sampled; the issue's 5.46 GB is the unsampled post-GC
peak at 10 GiB, which keeps garbage that G1 has not yet collected, and the same measure gives 5.1 GB
here): the live state 0.87, the encoded bytes 0.22, the generic tree of the read-back 2.85 and about
0.35 of the rest (the spike's own copy of the input among it). The tree is the one term that
a decoder change removes; the others remain.

**What the typed result is made of** (`GC.class_histogram` of the `HaskellLedgerFile` result, 842 MiB):
6.49 M `Credential.Key` objects, which is 10 x 648,752: the five proposals and their repeat in the
DRep `PulsingSnapshot`. Each costs a `Credential` (16 B), a `Bytes` (32 B), a 28-byte array (48 B)
and a red-black tree node (32 B): 128 bytes for 34 bytes of CBOR. The remaining 6 M objects of the
whole state are small beside them.

**The tree** (`GC.class_histogram` of the `Cbor.decode` result): `Bytes` 34.2 M objects, 1,094 MiB
(one window per item and one per string payload); `Node` 655 MB; `byte[]` 565 MB (the string
payloads, copied out of the input); `Value$UInt` 217 MB; the rest `Arr`, `Vector1`, `ByteString`,
`Tuple2` and `Object[]`.

To reproduce: build in the JDK image, `export node/Test/fullClasspath`, then
`java -Xmx<n>m -cp <classpath> xyz.kd5ujc.scarab.node.Memory94Main <cbor|ledger|v4|written|tape|votes> <snapshot>`;
`SAMPLE=1` forces a full GC every 200 ms and keeps the largest live set, `HOLD=1` keeps the result for
`jcmd <pid> GC.class_histogram`, `VOTES=<n>` sets the synthetic map's size.

## 3. Survey

### 3.1 Who depends on `Node` and `Value`

Files that mention `Node`, `Value.` or `Cbor.decode` (main and test): codec 3, crypto 3, models 7,
models-gen 2, ledger 33, consensus 1, protocol 6, network 6, node 11, storage 22, plutus 2, fuzz 2,
legacy 0.

Main sources only:

| Module | Hand-written decoders taking a `Node` | `new Codec[` instances | Callers of `Cbor.decode` (files) |
|---|---|---|---|
| models (`models/runtime`) | 25: the combinators `vec`, `pairs`, `tagged`, `embedded`, `nullable`, `Arr`, `Maps`, `Lit`, `Alternatives`, the leaf codecs | 18 | 1 (`Codec.decodeBytes`, `embedded`) |
| models, generated (16 files, 14.5 k lines, in `target`) | 482 `def decode(node: rt.Node)` | 0 (each type is an `rt.Codec` object) | 0 |
| ledger | 53 | 0 | 14 |
| storage | 61 | 58 (`haskell/state`: 304 mentions of `Codec[`) | 0 |
| node | 10 | 0 | 6 |
| protocol, plutus, crypto | 0, 2, 0 | 0 | 1, 1, 2 |
| legacy (frozen V2/V3 readers) | 0 | 4 (its own `Codec`, not the `Node` one) | 0 |

The generated models consume `Node` through three things only: the helpers `rt.Arr.exact/between`
(574 calls, returning `Vector[Node]`), `rt.Maps`/`MapParts` (map structs), and `node.original` for
the types that keep their wire form (29 sites; the Emitter writes `new T(...)(node.original)` at
`Emitter.scala:669`). 17 uses of `rt.Value.` pattern-match on a value. So the generated code is
independent of how a `Node` is stored as long as `value`, `original` and the helpers keep their
signatures.

### 3.2 How `Codec` and the decoders consume a node

- `Codec[A].decode(node: Node): Decoded[A]` (`models/runtime/Codec.scala`). `decodeBytes` runs
  `Cbor.decode` once, then the codec, and rebases error offsets onto the input.
- A record decoder (`Record.of`, `Arr.exact`) takes the node's `Vector[Node]` and calls the field
  codec on each element; a map decoder takes `Vector[(Node, Node)]`, decodes **all** pairs into a
  `Vector[(K, V)]` (`Elements.decodePairs`, `Maps.decodePairs`) and then sorts them into a
  `SortedMap` (`Wire.mapOf`, `Sorted.map`). So even with a free tree there are two transient
  collections of 648,752 tuples for each big map.
- Nothing retains the tree after the decode; the typed result holds `Bytes` windows only where a
  decoder keeps `original`.

### 3.3 Where `original: Bytes` is needed

About 60 uses in main sources, in three kinds. Only the first is the hard requirement.

1. **Hashed or signed, which must be the wire bytes** (anything hashed or signed is hashed over its
   original bytes): `crypto/Hashing.scala:36,44`; the header's signed body and VRF/KES inputs
   (`ledger/Header.scala:144,222,244`); block parts and the transaction body and witness bytes the
   block carries (`ledger/Block.scala:81-110`, `node/forge/BlockBuilder.scala:66-67`,
   `node/mempool/MempoolState.scala:222-225`, `LocalSubmission.scala:31`); the script data hash inputs
   (datums, redeemers, `Tx.scala:192-201,437-484`); native and Plutus script hashes
   (`Scripts.scala:201`); the Byron transaction id, update proposal and vote, certificates and
   addresses (`byron/ByronTx.scala:61-98`, `ByronUpdate.scala:50-81`, `ByronDelegation.scala:64`,
   `ByronAddress.scala:138`, `ByronLedger.scala:71-72`); the unspent output kept as received
   (`Address.scala:337,351`); the Leios votes (`protocol/LeiosNotify.scala`, `LeiosFetch.scala:90`).
   All of these items are small (a block is under 100 KB; the trees are short-lived).
2. **Carried opaquely**: `Codec.any` (`Codec.scala:199`), the Haskell state's items that are not
   modelled (`Envelope.scala:106`, `NodeTypeCodecs.scala:61,168`, `RewardCodecs.scala:93`,
   `UtxoCodecs.scala:62,66`), parameter-update values (`Tx.scala:252`, `Governance.scala:118`,
   `BootstrapLedger.scala:257`), set deduplication by window (`CborRead.scala:58`), the 29 generated
   `node.original`. These are slices of the input; an offset and a length describe them exactly.
3. **Error location and message only**: `DecodeError.at` (`DecodeError.scala:89`), `Codec.scala:29,278`
   (`unsafeOffset`), `Codec.scala:404` (a duplicate key's text). These need the start offset, not
   the bytes.

`Node.equals` also compares `original` (`Value.scala:21`), which the structural equality of tests relies
on.

Side finding for later: the opaque slices of kind 2, kept in a restored state, pin the whole input
array (`Bytes.slice` documents this; `compact` exists), so a state restored from a 233 MB snapshot may
keep that array alive for its life. A decoder that keeps an opaque window should `compact` it. This is
not part of #94.

## 4. The options

Haskell's shape, for reference (`cardano-ledger-binary` 1.9.1.0, under
`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/`): a `Decoder s a` is a function from the
original bytes and the version to a cborg `Decoder` (`Decoding/Decoder.hs:296`); a map is decoded
entry by entry with a strict key and value into a list and then `Map.fromList`
(`Decoder.hs:810-852`, `decodeMapByKey`, `decodeMapLikeEnforceNoDuplicatesInternal`); no tree exists.
The original bytes of an item are a `ByteSpan` of two offsets and `slice` of the shared input
(`Decoding/Annotated.hs:69-79`, `annotatedDecoder` `:112-114`, `decodeAnnotated` `:130`, `withSlice`
`:227-231`). Repeated keys are shared by interning against a map already decoded
(`Decoding/Sharing.hs:53-66,111-135`).

### Option 1: an indexed `Node` (no object per item)

`Cbor.decode` runs the explicit-stack loop it runs today, but writes two ints per item into arrays
(where the item's head starts; how far its subtree reaches) and builds no object. It also checks what
it checks today (limits, UTF-8, trailing bytes, map arity). A `Node` becomes `final class Node(doc,
index)`, 24 bytes, created when a decoder asks for a child. `Node.value` builds the value of one level
(`Arr(Vector[Node])` of fresh views, `UInt`, a string) from the head at `starts(index)`;
`Node.original` is `Bytes.wrap(data, start, end - start)`; a new `Node.offset` gives the start for
error locations without a `Bytes`. `Value`, `Codec`, the helpers and the generated code do not change.

- **Heap**: the index is 8 B an item, 219 MiB for this file (259 MiB measured with doubling slack,
  which a first pass over the input to count the items would avoid), and the tree of 2.85 GB goes.
  Measured: building it takes 0.37 s against 1.8 s for the tree, and the bisected minimum heap for
  the build is 552 MiB including the 222 MiB input. Estimated minimum for `HaskellLedgerFile.decode`:
  input 0.22 + index 0.26 + typed 0.84 + the pair vectors of one big map 0.1 = about 1.4 GB (not yet
  measured, because the decoders run only on a real implementation).
- **Blast radius**: `codec` (`Value.scala`, `Cbor.scala`; `Tree.scala` and `TreeFold.scala` are
  generic and stay), and one line in `models/runtime/DecodeError.scala` and `Codec.scala` to use
  `offset`. No decoder, no generated file, no `Emitter` change. The test that builds `Node(value,
  bytes)` by hand (`CborSuite.n`) uses a strict constructor that stays.
- **Risk**: (a) `value` must be a `def`: a cached `lazy val` would memoise the tree as the walk
  proceeds and bring the 2.85 GB back. Its cost is that a decoder that reads a node twice parses it
  twice (`Alternatives`, `maybeTagged`, `Codec.describe`); decoders for items of a few hundred bytes
  do not notice, but the per-block path (`DecodeBench`) has to be measured. (b) `Value.Arr(Vector[Node])`
  of a 648,752-entry map is about 50 MB of views; it is transient only if nothing retains the parent
  `Value`, which no decoder does today and a test can pin (a `Vector[(Node, Node)]` must not be stored in
  a result). (c) 32-bit offsets cap an input at 2 GiB, which `Limits.maxInputBytes: Int` already does.
  (d) The index builder is the one place that must equal today's decoder error for error: the
  differential test of section 7 is the guard.
- **Original bytes**: `original` is a window of the same input bytes, so a hash over it is the hash
  over the wire bytes, including indefinite lengths and non-minimal heads; this is the `ByteSpan`
  of Haskell. The end of an item is the end of its last item, plus one byte for each indefinite
  container that closes there; the layout of the index (`skips` as now, or `ends`, with the next
  sibling found by a galloping search on the sorted `starts`, which keeps 8 B an item) is decided in
  stage 1 by the same spike.
- **Stack safety**: the builder is an explicit stack machine (as `Cbor.Decoder` already is), `value` is
  one level and not recursive, the `Tree[Node]` walks (`equals`, `hashCode`, `render`) stay
  iterative. Nesting of 10 k levels is the existing `CborSuite` test.

Variant without an index (a `Node` is `(data, start, end)` and its children are found by scanning):
no memory beyond the input, but finding the children of a node scans the whole subtree of each
child, so reading a level costs the size of the subtree and the whole decode costs size x depth. For
a ledger state of depth 10 to 15 that is a 10-fold slower decode of 217 MiB; rejected.

### Option 2: streaming typed decoding of large collections

Decode the entries of a large map or array straight into the result, with no `Vector[(K, V)]` and no
subtree held. On its own (without an index) it needs the *parent* to hand the child codec a cursor, not
a `Node`, along every path from the root to the big map (file, telescope, ledger state, new epoch
state, epoch state, ledger state, gov state, proposals, a proposal, its votes): that is option 3 in
miniature, with a second method on `Codec` that every codec on the path has to implement. On top of
option 1 it is small: give `Node` a `foldChildren`/`entries` (an iterator or `foldLeftM`, in the library
style of [ADR 0011](../adr/0011-stack-safety.md)) and change `Elements.decodeAll`, `decodePairs`,
`Wire.mapOf/setOf/seqOf`, `Codec.vec`/`pairs` to fold into a `SortedMap`/`SortedSet`/`Vector` builder.

- **Heap**: removes the transient 0.1 GB per big map and, without an index, the 0.22 GB of it. After
  options 1 and 2 the read-back peak is dominated by the typed states.
- **Blast radius**: the 10 combinators in `models/runtime/Codec.scala` and `storage/.../Wire.scala`;
  records are untouched.
- **Risk**: error paths (`key[i]`, `value[i]`) and the version-dependent duplicate checks
  (`v.atLeast(9)`) must be kept; a fold that short-circuits on the first error must stay stack safe
  (`foldLeftM` over `Either`).
- **Original bytes, stack safety**: as option 1.

### Option 3: a pull reader under `Codec` (Haskell's `Decoder`)

`Codec[A]` gets `read(r: Reader): Decoded[A]` over a cursor on the input (peek the major type; read
`uint`, `bytes`, `arrayLen`, `mapLen`; `skip` an item; `span` of an item). No tree and no index: the
input array plus the typed result. `Codec.any` and the opaque items become `r.span(...)`; hashing
uses the span's slice of the input, as `withSlice` does (`Annotated.hs:227`). The reader must be able to
mark and reset for `Alternatives`/`maybeTagged` (a cursor is an offset, so this is cheap, unlike
cborg's). `skip` of an item and every recursion over a data structure that comes from outside
(Plutus data, native scripts, nested Byron items) must be an explicit stack machine, not recursion
in the reader.

- **Heap**: input + typed only: about 1.95 GB for the epoch-188 read-back (estimate), 0.26 GB below
  option 1 for this file. The same saving for any file; a pure function of the typed result.
- **Blast radius**: every decoder: the 482 generated `decode`s and the Emitter and its 574 helper
  calls, the 53 ledger and 61 storage hand-written decoders, `Wire`, `Record`, `MapSpec`/`MapParts`
  (a map struct needs random access to entries by key, which a one-pass reader does not give; the
  struct decoder would collect `(key, span)` pairs first), the CDDL cross-check and the fuzz
  targets, `DecodeError` location. Roughly 700 decoders for the same memory behaviour that option 1
  gives with changes in two files.
- **Risk**: highest. The decoders are the most tested code in the repository (golden blocks, cuddle
  cross-check, fuzz), and a one-pass reader changes their control flow (ordered choice, optional map
  keys in any order, `Alternatives`). Stack safety needs the explicit machine everywhere a decoder
  recurses on the data.
- **When it pays**: a state whose *index* does not fit (a mainnet-scale section of several GiB),
  or hot paths where the per-`value` parse is measured to hurt. Neither is measured; the plan keeps the
  door open by making `Node` an interface to a cursor, which is what a `Reader` over the same index is.

### Better option found: share what repeats (`Interns`)

The typed state, not the tree, is what stays. 6.49 M `Credential.Key` copies for 648,752 distinct
voters (10 copies) cost about 0.62 GB of 0.84. Haskell's decoder interns keys against a map that
exists already (`DecShareCBOR`, `Sharing.hs:53-66`, `internsFromMap` `:122`). Here: a `Codec` of
credential and hash keys that returns the first equal instance it has seen in this decode
(`Interner[A]`, a `HashMap[A, A]` created by `HaskellLedgerFile.decode` and given to the codecs with
`using`, so that a decode shares and nothing global exists). Estimate: typed 0.84 to about 0.35 GB;
the restored node also runs on the smaller state (about 0.5 GB less heap in epochs 188 to 193), and
the repeated maps (`proposals` and the pulsing snapshot's copy) could be made one object when equal.

- **Blast radius**: `Wire` (the key codecs) and `HaskellLedgerFile.decode`; no wire format change.
- **Risk**: low: values are immutable, and equality is unchanged. The interner is a per-decode
  mutable table; it is a `Stateful`-style capability in the codec construction, not a global.

### Write path copies (small, independent)

`SnapshotV4.encoded` makes `haskell.toArray`, `framed` copies it into a `ByteArrayOutputStream` and
`toByteArray` copies again, then `FileLedgerSnapshots.closed` appends the checksum (`content ++
sha`, a fourth copy): about 0.7 GB of 222 MiB arrays, each a G1 humongous allocation that needs a
contiguous run of regions (a hazard at a 3 GiB heap). Writing the sidecar, the Haskell bytes and the
checksum to the file in turn needs none of them. Not measured separately; the spike's `written` mode
measures it.

### Comparison

| | Tree today | 1 indexed `Node` | 1 + 2 | 3 pull reader | + interning |
|---|---|---|---|---|---|
| Section decode, retained beyond the typed result | 2.85 GB | 0.22 GB (index, measured 0.26) | 0.22 GB | 0 | |
| Typed result | 0.84 GB | 0.84 | 0.84 | 0.84 | about 0.35 |
| Read (restart) min heap | 3.4 GB measured | about 1.4 GB (est.) | about 1.3 GB | about 1.1 GB | about 0.9 GB |
| Read-back peak at write (live 0.87 + encoded 0.22 + back) | 4.5 GB measured | about 2.3 GB (est.) | about 2.2 GB | about 2.0 GB | about 1.7 GB |
| Files changed | | 4 | about 10 | about 700 decoders | 3 |
| Decoder code changed | | none | the combinators | all | key codecs |
| Risk | | medium | low on 1 | high | low |

The estimates add the measured parts (live state 0.87, encoded bytes 0.22, typed result 0.84, index
0.26) and a stated 0.1 for pair vectors. They are not measurements of an implementation.

## 5. Recommendation

1. **Option 1** first. It removes 2.6 GB of the 4.5 GB peak and the whole 3.4 GB read minimum with
   changes in two source files, and it keeps the owner's style intact: the same `Codec`
   type class, the same `Node`/`Value` algebra, the explicit stack machines of ADR 0011. It is the
   Haskell node's own mechanism for original bytes (`ByteSpan`), so the hashing rule is kept by
   construction.
2. **Option 2** as its second stage (folds into builders), because it is a small, local change that
   the first makes cheap, and it removes the transient copies that the bound of the regression test is
   most sensitive to.
3. **Interning** as the third, because it is the largest remaining term and the only one that
   also lowers the node's steady-state heap through epochs 188 to 193.
4. **The write path copies** at any point; independent.
5. **Option 3** is not recommended now: about 700 decoders for 0.26 GB that stage 1 does not
   save, with the highest risk. Revisit it if a mainnet-scale section's index no longer fits, or if
   the per-`value` parse shows in `DecodeBench`.

A new ADR (0012, "CBOR documents are an index over the input") would supersede the sentence of ADR 0005
that says the decode builds "our own tree"; its consequences keep the other four.

## 6. Does it fit in 3 GiB?

After stage 1, at the write (epoch 188): live state 0.87 + the encoded bytes 0.22 + the index 0.26
+ the typed read-back 0.84 + the transient pair vectors 0.1 = about 2.3 GB, which leaves 0.7 GB of
a 3 GiB heap for young generations and G1's regions; the live state holds `want` (the canonical form
of the state) by sharing with the live state, which has not been measured. That is thin. Stage 2
brings it to about 2.2, and the interning stage to about 1.7, which is the margin the preprod run through
epoch 194 should be given. If a later epoch's state grows past this, the fallbacks are the write
path copies (0.7 GB) and, as a decision for the owner and not part of this plan, a read-back that
re-encodes and compares bytes instead of holding two typed states.

## 7. Staged plan and acceptance

Each stage is one session, with the gates of AGENTS.md (`check`, `scalafixAll --check`).

- **Stage 0, the write path copies.** Write the file in pieces. Acceptance: `Memory94Main written`
  sampled peak lower by about 0.6 GB; the snapshot bytes are identical (the `CodecSuite` fingerprint
  golden and `SnapshotV4Suite`).
- **Stage 1, the indexed `Node`.**
  - The old builder stays in the test tree as the oracle. A differential property test: for every
    fixture block, transaction and snapshot in `fixtures/`, for the fuzz corpus and for generated
    items with indefinite lengths, non-minimal heads, floats, bignums and 10 k nesting, the new
    `Node` and the oracle give the same `value`, the same `original` bytes and the same error
    (message and offset). `CborSuite` and every existing suite pass unchanged.
  - Hashing: the existing golden block, header and transaction ids (`BlockGoldenSuite`,
    `GoldenHeaderSuite`, the mainnet and preprod Byron suites) pass unchanged; a new test hashes
    `original` of every node of a fixture block and compares with the bytes sliced from the input.
  - Memory (a forked JVM, since the heap bound has to be real, as `Test / fork` with
    `-Xmx256m`): decode a **synthetic 1 M-entry votes map** (the shape of the spike's `votes` mode,
    `{[0, h28] => 0..2}`, 31 MiB) through `Wire.mapOf` and check it. Today it needs 587 MiB
    (typed result 123 MiB), so 256 MiB fails now and must pass; the bound is revisited down after
    stage 2 (expected about 200 MiB).
  - The epoch-188 read-back in a 3 GiB heap: `Memory94Main v4` and `written` with `-Xmx3g` (as a
    gate beside `PreviewMirrorMain`, not a unit test: the input is not in the repository). Expected
    to pass with about 0.7 GB to spare.
  - CPU: `DecodeBench` (models) not more than 15% slower on the block path; the 217 MiB section
    decode not slower than today (6.5 s).
  - Stack: the 10 k nesting tests and a 256 KiB thread (`onSmallStack`) for the builder, `value` and
    the walks.
- **Stage 2, folds into builders.** Acceptance: `Wire`/`Codec` collection decoders do not allocate a
  `Vector[(K, V)]` (the votes test at a tighter bound); error paths and the `atLeast(9)` duplicate
  rule keep their tests (`HaskellStateCodecSuite`).
- **Stage 3, interning.** Acceptance: the retained typed result of the epoch-188 file at most 0.4 GB
  (`Memory94Main ledger`), equal states (`==`) with and without sharing, and the preprod run through
  epoch 194 at 3 GiB.
- **Stage 4 (only if measured to be needed)**: option 3 for the one hot path that needs it.

Open for the owner: whether the read-back of section 6 stays a full typed comparison; and
whether `Node.value` caches leaves (it does not in this plan: a cache is retained heap).

*Decided 2026-10-16:* the owner keeps only the latest robust path ("I didn't think we need to
maintain two copies of ledger state if we have migrated. I am happy to keep only the most recent
robust code"). The node writes with `SnapshotCheck.Structure` (the sidecar decodes and the Haskell
section is one CBOR item, read as the index and dropped); `SnapshotCheck.Full`, the typed read-back
and comparison, is what the tests, the migrations and `--snapshot-check full` use. The recovery
already restores the newest snapshot that reads, so a file that parses and does not restore costs
a slower restart, not a wrong state.

## 8. Results of stages 0 and 1 (2026-10-16)

Measured as in section 2 (JDK 21, G1, Docker, `Memory94Main`), on a 233,415,408-byte snapshot of the
issue's epoch-188 range (content 222 MiB, Haskell section 216 MiB, 27.3 M items). The file is a copy of
the one in the heap dump taken for the issue: the `db/snapshots` of the live node no longer holds a
file of that size (its snapshots are now 20 MB, the proposals having expired). "Before" is the commit
that carries this document (`c0b9326d`), built and run the same way; the minimum heaps are bisections
to 16 MiB.

| | Before (the tree) | After stages 0 and 1 |
|---|---|---|
| `Cbor.decode` of the Haskell section, retained | 2,849 MiB, 1.7 s | **209 MiB** (7.7 B an item), 0.24 s |
| `Cbor.decode`, minimum `-Xmx` | 3,093 MiB | 450 MiB |
| `HaskellLedgerFile.decode`, retained (the typed state) | 842 MiB, 6.5 s | 842 MiB, 3.9 s |
| `HaskellLedgerFile.decode`, minimum `-Xmx` | 3,406 MiB | **1,284 MiB** |
| `SnapshotV4` read, minimum `-Xmx` | 3,449 MiB | **1,326 MiB** (6.9 s, was 9.6 s) |
| `SnapshotV4` write with its read-back, `-Xmx3g` | out of memory | **passes** |
| the same, live set at its largest (sampled) | 4,535 MiB | **2,395 MiB** |
| the same, peak after any collection at `-Xmx24g` | 5,276 MiB | 2,470 MiB |
| the same, minimum `-Xmx` | not run | 2,402 MiB |
| committee votes, 1 M entries, 32 MB (`MemoryBoundSuite`) | `-Xmx256m`: out of memory (587 MiB needed) | **passes at 256 MiB** (between 192 and 224 MiB needed) |
| `DecodeBench`, generated Conway header | 716 to 791 ns | 573 to 754 ns |
| `DecodeBench`, hand-written `Header.fromRaw` | 1,810 to 2,033 ns | 1,756 to 1,948 ns |
| `DecodeBench`, generated ChainSync `RollForward` | 1,254 to 1,523 ns | 1,233 to 1,486 ns |
| `DecodeBench`, generated Handshake propose | 765 to 875 ns | 780 to 991 ns |
| `DecodeBench`, `Cbor.decode` of a header | 686 to 1,159 ns | 155 to 173 ns |

The typed state is unchanged (842 MiB), as expected; so is everything the read-back compares. The
write at 3 GiB passes with about 0.6 GiB to spare, which is what section 6 estimated (2.3 GiB).

What was found, against the plan:

- **The index** is two ints an item, start and end of the item (not the start and the subtree's
  count): the end gives `original` in O(1), and the next sibling of an item is found by a galloping
  search of the sorted starts for the item that begins where it ends. It is kept in blocks of 4,096
  ints (the first block grows from 32 by doubling), so it is never copied as it grows and a short input
  does not clear kilobytes: the first version sized its arrays for the input and made a 857-byte
  header 70% slower to decode; sizing them for the items brought the whole of `DecodeBench` to the
  old figures, within the noise of the machine (the runs of one build differ by up to 20%).
- **Stage 0 does not move the peak.** The copies it removes (`toArray`, the output stream, its
  `toByteArray`, the checksum's `++`: four 222 MiB arrays, each a humongous allocation) are
  garbage by the time the read-back runs, and the read-back is where the peak is: sampled live peak
  4,535 MiB with and without it on the old tree, minimum heap of the write 2,402 MiB with and without it
  on the new decoder. It lowers the allocation of a write by about 0.9 GB and the contiguous regions
  it needs. It is kept because it is exact (the bytes are those of the old framing, tested on every state
  of the eras fixture) and small. A second copy, in `Writer.result` (`copyOf` and then `fromArray`'s
  clone), went with it.
- **Stage 1 needed a slice of stage 2.** With the index alone the votes map did not fit in 256 MiB
  (it needed between 256 and 320 MiB): `Value.Map` holds a vector of the 1 M pairs of views (about 76 MB) next to
  the vector of decoded pairs. `Node.entries`, an iterator over the index, and `Wire.mapOf`, its one
  reader, remove the first; no decoder reads it yet but that one. The other collection combinators
  (`Maps.decodePairs`, `Wire.setOf`, `seqOf`, `Codec.vec`, `pairs`) are stage 2.
- **`Node` is a sealed abstract class**, not a case class: a view of the index (`Indexed`) and a
  node built by hand (`Node.apply`, used by the Leios and Handshake encoders and the tests). Nothing
  matched on it or copied it.
- No decoder, generated file or `Emitter` changed. `DecodeError.at` and two lines of
  `models/runtime/Codec.scala` read `node.offset` instead of `node.original.unsafeOffset`, which
  saves a window per error.

For stage 2: fold the children of the large collections into their builders (`Node.entries` is
the first of a `children` iterator for arrays and sets); then tighten the bound of `MemoryBoundSuite`
(expected about 200 MiB); the write at epoch 188 is then dominated by the live state (1.1 GiB), the
typed read-back (0.84 GiB) and the index (0.21 GiB), which stage 3 (interning) shrinks.

