# Original-byte body commitments (v0.20)

`CardanoBodyCommitment.inspect(raw, CardanoBlockIndex.Limits())` is a pure
commitment predicate for original disk-era envelopes, Shelley through Conway.
It returns typed malformed/internal failure or an immutable private-constructor
`Observation`. A well-formed mismatch remains an observation with independent
size/hash booleans. The observation binds the result to `rawSha256`, `headerHash`
and an explicit era; it exposes declared/actual sizes and hashes plus ordered
component kinds, original offsets, lengths and digests. It retains no full body
copy and offers no public constructor/copy method for trusted observations.

## Algorithm and byte boundaries

For each complete original component `Ci`, `H` is native-output BLAKE2b-256:

```
actualHash = H(H(C0) || H(C1) || H(C2) [|| H(C3)])
actualSize = length(C0) + length(C1) + length(C2) [+ length(C3)]
```

Tags 2/3/4 (Shelley/Allegra/Mary) have transaction-body array, witness array and
auxiliary-data map. Tags 5/6/7 add invalid-transaction-index array. Component heads
and indefinite breaks are included; outer envelope, outer block head/break and
header are excluded. The outer hash consumes exactly 96 or 128 raw digest bytes,
without CBOR byte-string headers. Flat hashing of concatenated components is wrong.
No CBOR encoder participates. Header declaration positions are 7/8 for TPraos
(Shelley through Alonzo), and 6/7 for Praos (Babbage and Conway).

All six era implementations are source-pinned in the portable corpus (optional private corpus; not distributed).
The cached group encoders emit original component bytes even when a protocol
version is supplied to the size method. This supports sum-of-original-lengths;
it does not establish exact generic decoder acceptance for every historical case.

## Reuse and preserved behavior

`CardanoBlockIndex` now constructs one package-private `ParsedBlock` containing its
unchanged index, era, declarations and already-owned immutable component originals.
Both public inspectors use that validated parse. The body inspector does not call
public indexing followed by a second CBOR decode, nor accept caller-forged nodes.
Existing public index signatures, errors, six-era whitelist, original SHA/header
identity and shallow numeric/schema profile remain unchanged. It still accepts
uint64 body declarations; Word32 is tightened only by this new predicate.

No fetcher, source descriptor, manifest, checkpoint, resume format, retry policy,
request, transport or persisted identity changes. Existing structural body-mutation
regressions remain positives for structural indexing. Strict admission and durable
receipts are the next separately versioned integration policy, not an implicit
change to old data. Raw SHA/header identity in the observation provide bindings
for that work; an old structural record is not a body-check receipt.

## Resource and ownership contract

- Same hard maxima as the index: 1,048,576 input bytes, depth 32, 100,000 total CBOR
  items including indefinite string chunks, 1,048,576 bytes per logical string.
  Caller limits can only tighten these. They are local work limits, not historical
  protocol maximum-block-size parameters.
- Declared size is unsigned Word32, represented as nonnegative Long after the
  checked BigInt bound. Actual size accumulates with checked Long arithmetic;
  offsets use checked Int arithmetic before new body hashing. Declared size never
  drives an allocation. Only three/four component digests plus one outer digest.
- CBOR/tree allocation is bounded by input, item and depth ceilings. Nested
  `Node.original` copies can require input-size times depth memory plus objects;
  “1MiB input cap” is not a “1MiB peak-memory” promise.
- `Bytes` and output vectors are immutable. Input/output array boundaries copy.
  No I/O, ambient state, global digest caches, `unsafeRunSync` or ledger state is
  present in the pure predicate. Each hash uses a fresh digest instance.

## Bounded read-only CLI

```
./scripts/sbtw 'app/run body-commitment fixtures/body-commitment/blocks/mainnet-conway-0.cbor'
python scripts/verify-body-commitment-projector.py
python scripts/verify-body-commitment-cli.py
```

The JVM command takes exactly one local raw binary disk-block file, or `--help`.
It rejects symlinks, directories and special files during regular-file preflight,
opens with `NOFOLLOW_LINKS`, and reads at most 1MiB plus a rejection sentinel through
an owned channel. It makes no URL/network requests and writes no input/store files.
The local filesystem must not be adversarially replaced between preflight and open;
this is not an atomic hostile-filesystem sandbox or a hard disk-latency guarantee.

One bounded JSON object includes identities, component descriptors, separate size
and hash outcomes, structural status, and `not_checked` for header cryptography,
consensus, ledger and source authentication. Exit 0 means both declarations match;
exit 1 means a well-formed mismatch; exit 2 covers malformed input, file/usage errors.
The sbt run wrapper may collapse nonzero application codes to build failure 1.

## Evidence, malformed controls and limits of the claim

The independent Python stdlib projector neither imports Scala/acquisition parsers
nor re-encodes original blocks. Thirty-six originals produce 36 hash matches and
35 size matches. All 16 newly retained source-labelled mainnet samples match both.
The Conway toy's declared size 2345 versus original size 6950 remains negative.
No real input has nonempty invalid indices, so richer index cases are synthetic.

The 347 explicit synthetic cases cover all components and six eras, uint32/uint64
boundaries, hash/size independence, same-length mutations, original indefinite/wide
heads, outer-versus-header identity, arities/types, malformed CBOR/chunks/UTF-8,
truncation/trailing bytes, and coordinated semantic invalidity. Additional Scala
production-API tests exercise exact hard input/depth/item ceilings plus one, all
small-block prefixes, cumulative string limits, null input, tightened bounds and
array immutability. Full matrix: [test coverage](body-commitment-test-matrix.md).
Executed checks and unrun gates are kept in [verification](body-commitment-verification.md).

Mismatched transaction/witness counts, auxiliary index duplicates/range and invalid
index duplicates/order/range are intentionally not upgraded into ledger checks.
Coordinated header/body changes can match commitments while changing header identity
and violating ledger semantics. Such matching tests are scope negatives, never
valid-block positives. Reference null genesis parents remain unsupported by the
shared index profile. A hash match authenticates neither the chosen header nor
its source, pool registration, KES/VRF eligibility, transactions or ledger state.

Fresh exact-release Haskell decoder/hash/size comparisons and broader historical
encoding/nonempty-invalid-index fixtures remain open evidence gates. The immediate
next engineering step is one evidence-typed same-block ingestion pipeline and
versioned receipts, followed by restricted reversible ledger replay; additional
disconnected demonstrations are not the intended validator architecture.
