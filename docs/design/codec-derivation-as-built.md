# The typed codec derivation, as built (#205)

#205 asked for the erased value channels in the storage codecs to go, with the persisted bytes,
the record order, the variant tags, the `shape` strings (schema fingerprints) and the Haskell
error paths unchanged. This note records what was built, what it costs, and what it does not do.

## What changed

Three files, one idea: a derived codec no longer carries a `Codec[Any]` and a projection; it
carries one codec per field (product) or per variant (sum), and each value is narrowed to the
declared type by that type's `ClassTag` extractor.

- `storage/Codec.scala` — the derivation by `Mirror` moved from an inline site to the
  `Fields[Ts]` / `Variants[A, Ts]` chains, built once per derived type by implicit search
  (`LowPriorityCodec`). `Fields.write` walks the product in declaration order and writes each field
  with the codec summoned for its declared type; `Fields.read` reads each field with that codec and
  accumulates the tuple the mirror's `fromProduct` takes. `Variants` writes the tag, then the
  matching case's fields, and reads the tag and dispatches to the case's codec (arity and tag
  errors are the same `BinaryError`s as before).
- `storage/haskell/state/Record.scala` — the same shape for Haskell records: `Record.of[A]` takes
  one codec per field (`Tuple.Map[m.MirroredElemTypes, Codec]`), walks it together with the value
  taken by `Tuple.fromProductTyped`, and hands the result to `m.fromProduct`. `Record.of`/`auto`
  now require `A <: Product` — always true at their case-class call sites.
- `storage/NodeCodecs.scala` — `withOrigin[A]` adds the `ClassTag[A]` its `Codec.auto` derivation
  needs.

There is no `Codec[Any]` and no unchecked projection in the source. The one cast is inside the
standard library's `ClassTag.unapply`, which performs the class test and returns the narrowed
value; the source contains no `asInstanceOf`.

## What is preserved, and how it is checked

- **Bytes.** The format goldens and the byte-level comparisons (`FormatGoldenSuite`,
  `FormatMigrationSuite`, `HaskellFormatsDiffSuite`, `LedgerSnapshotsSuite`) pass 42/42, and the
  benchmark encodes byte-identical output: `c19600a83b7ba784` (333 B, nested synthetic record) and
  `9b5615ce2d835aac` (34 B, `FutureGenDeleg` through `Record.auto`), decoded values identical too.
- **Field order and variant tags.** Both derivations walk `Mirror` element order and use the same
  tag numbering; `Record` takes field names from `MirroredElemLabels` in the same order.
- **`shape` strings.** Each codec's `shape` is unchanged, so schema fingerprints are unchanged —
  no ADR 0006 migration is involved, because the format did not change.
- **Error paths.** Decode errors carry the field labels (`Fields.decode` takes the label vector and
  reports the field index in the path) exactly as the previous derivation did.

## What it costs

Encoding is 13–71% slower on the measured workloads: `Codec.auto` on a nested 333-byte record
188.6/194.7 → 220.5/321.9 ms per 20,000 encodes, and `Record.auto` on `FutureGenDeleg`
4.058/4.783 → 5.546/6.383 ms per 20,000. Decoding is neutral: 7% faster on the synthetic record
(273.3/284.2 → 250.4/263.6 ms), and on the real record +5% by minimum (+25…+58% by median across two
runs) — 17.905/20.439 → 22.366/32.222 ms. Allocations are within ~2%.

The cost is the narrowing itself: a `ClassTag` test and the `Some` it returns per field, and
`Tuple.fromProductTyped` per record in `Record`. That is the price of removing the unchecked
projection without a cast in source; removing it as well would need compiler-generated typed
accessors that Scala 3.3 does not provide for a generic `A`.

## What this does not do

- No format change: no ADR 0006 version bump, no migration, no fingerprint movement.
- It does not make encoding faster. #205's acceptance criterion is correctness and unchanged
  persisted bytes; the performance criterion is the owner's decision (accept the encode cost for
  cast-freedom, or hold this implementation).
- The storage `restart` area scenario has not been run — the devnet host's follower launch is
  broken (see the ledger `e2e` failure on #248) — so this note claims the suites and the byte
  comparisons, not a devnet verdict.
