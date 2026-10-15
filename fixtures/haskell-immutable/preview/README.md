# Haskell ImmutableDB fixture (Preview, first three chunks)

`immutable/00000` to `00002` (`.chunk`, `.primary`, `.secondary`) and `protocolMagicId` are
byte-for-byte copies of the first three finished chunks of a `cardano-node` 11.1.3 database that
synced the public Preview network (copied 2026-10-15 from a database the node was still writing at
its tip; chunks 0 to 2 are long finished). Preview has `k = 432`, so a chunk is 4,320 slots and each
file trio covers one third of one of our 21,600-slot chunks. 648 Alonzo blocks (slots 0 to 12,940),
no Byron blocks, no epoch-boundary blocks. About 0.7 MB.

`expected-blocks.tsv` lists, for every block, `slot`, header hash, block number, SHA-256 of the block
bytes and its length. It was produced by reading the blocks our own ImmutableStore holds for the same
slots (the K1 Preview sync, `.workspace/data/2026-10-14-k1-scala-preview`) and checking, with an
independent script, that their bytes concatenate to exactly the three Haskell chunk files. The
reader and importer tests (`HaskellImportSuite`) compare against it, so they show that the Haskell
database and our store hold identical blocks.

Epoch-boundary blocks: there is no Byron database at hand, so `HaskellImportSuite` builds a
Byron-to-Conway database from `fixtures/eras` by the encoding rules of ouroboros-consensus 4.2.1.0
(`HaskellDbKit`) instead.
