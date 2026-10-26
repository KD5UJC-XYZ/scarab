<!-- SPDX-License-Identifier: Apache-2.0 -->
# Persistence: Haskell ImmutableDB format

Part of the [compatibility matrix](../compatibility.md); the register entry is `persistence-haskell-immutabledb` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

reader only: `HaskellImmutableReader` and `import-haskell-db` read a Haskell node's `immutable/` (`NNNNN.chunk` / `.primary` / `.secondary`, chunk size 10k slots, CRC32, header hash, slot and offset validation, newest chunk cut back) and append its blocks to our own `ImmutableStore`, which keeps its own format; writing the Haskell layout is not implemented

## Evidence

pure parsers with round-trip and corruption properties (`HaskellIndexSuite`: every truncation, flipped CRC, offset, hash, slot or version is a typed fault); three real finished Preview chunks (648 blocks) equal to the blocks of our store byte for byte (`HaskellImportSuite`); import, stop slot, resume, wrong magic, wrong genesis, damaged chunk; end to end: 200 Preview chunks (40,357 blocks) imported in 2 s, replayed in 42.7 s and followed on from the intersection at their tip

## Explicitly unchecked or unconfirmed

Byron epoch-boundary blocks are only tested on a database built by us from the upstream encoding rules out of `fixtures/eras` (no Byron Haskell database was available); Byron header hashes are not checked against the index by the reader (the importer's block decoding does); the Haskell ledger snapshots (`ledger/`) and the volatile DB are not read; the reader assumes chunks are contiguous from 0 and the newest chunk is the highest number
