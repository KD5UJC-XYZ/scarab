<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native ImmutableDB comparison corpus

This logical corpus includes `preview/immutable/`, `preview/protocolMagicId` and
`preview/expected-blocks.tsv`. The [Preview capture README](preview/README.md) records the original
cardano-node 11.1.3 provenance, exact three-chunk/648-block scope, capture date and construction of
the independent expected block inventory. The files are unchanged native public Preview data.

`HaskellImportSuite` reads/imports these original files and compares block slots, hashes, numbers,
lengths and SHA-256 values with that inventory. `HaskellIndexSuite` also pins observed index-layout
bytes and exercises malformed/torn input. Synthetic Byron/epoch-boundary inputs use `HaskellDbKit`
and are separate generated controls: this capture contains Alonzo blocks only.

Never refresh expectations from the implementation under test. Extending or replacing a native
capture requires original reference files, pinned node/network/chain provenance and an independently
checked block inventory; review both bytes and expectations. These bounded committed inputs run in
ordinary tests. The optional `SCARAB_K1_DB` whole-database differential compares against legacy Scala
parsers on external native files, asserts nonempty input, and is a distinct regression proof rather
than an independent Haskell parser oracle or proof that every native chunk was accepted.


The external index comparison requires matching nonempty `.chunk`, `.primary` and `.secondary`
stem inventories and successful parses on both Scala implementations, with positive decoded entry
and offset totals. Closed chunks reject torn tails. Only the greatest numeric chunk tolerates
partial index tails, as `HaskellImmutableReader` does; exact primary/secondary tail-byte counts are
reported separately and never count as decoded coverage. Original files remain untouched and their
identities belong to the approved external artifact. This compares persisted complete entries, not
unpersisted tail contents or an independent native parser verdict. A newest wholly empty primary
index is outside this successful-parser comparison; the reader's empty-index initialization has its
own tests. `NativeIndexComparisonSuite` keeps malformed, orphaned, empty and bounded-tail controls
separate from native acceptance.
