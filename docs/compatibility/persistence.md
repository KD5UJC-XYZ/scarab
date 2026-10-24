<!-- SPDX-License-Identifier: Apache-2.0 -->
# Persistence (UTxO-HD style)

Part of the [compatibility matrix](../compatibility.md); the register entry is `persistence` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: `ImmutableStore`, `VolatileStore` (the last `k` blocks, crash-safe log), `LedgerSnapshots` (periodic and on graceful stop), RocksDB `UtxoStore`, network identity record, restart recovery

## Evidence

round-trip and corruption property tests; store crash cases (torn data/index, every cut point of the newest chunk); `VolatileStoreSuite` (model equivalence over random appends, rollbacks, collections and reopens; every crash cut point; damage in an older file); `UtxoStoreSuite` equals a `Map` model across rollbacks and flushes; restart equivalence over the Byron-to-Conway capture with and without the volatile log; a graceful stop followed by a start that replays 0 blocks; copies of a running database at 12 points restart to the same state; identity created, reopened, refused on four kinds of mismatch, adopted and refused for a database without one; #159 verifies explicit V1/V2 adoption, incomplete/missing identity refusals, byte-preserving preflight, and generic snapshot migration to V5; full Docker check (4,721 passed, 15 skipped) and explicit scalafix pass; extended fuzz: 71 targets × 400,000 cases, including versioned identity parse/render/parse; private Conway restart (`scarab-159-restart-luna-ab391b50`, exit 0): graceful stops 0.8 s and 0.7 s, one snapshot restored, replayed 0 blocks after restore, two shutdown snapshots, no error/fatal events, snapshots remain `SCRBSNP5`, and V6 Alonzo/Conway bindings match BLAKE2b-256 of exact generated genesis bytes (`d9cb5f26ffb1f9d9cd16bb2921bb959ac405ded4dd338f11b21032378531a5bc`, `b4506e8a66fafc1db80d2c5d3daf51d2a70702b1c0888890560e318e1bdca512`); runtime class-directory inventory SHA-256 `eb646146d3d342d855afd78c3b2161020377e9ebbc4cc98ac600a9bcac3ce515` at `ab391b50`; live Preview restarts (graceful: 0 blocks replayed, 432 volatile blocks resumed in 4.7 s; `kill -9`: 16 blocks replayed, 432 resumed, intersection = the last block before the kill)

## Explicitly unchecked or unconfirmed

only the UTxO set is on disk; accounts, pools, DReps and stake snapshots are in memory and in the snapshot file (D1); a `kill -9` replays the stored blocks since the newest periodic snapshot (up to one epoch by default); the volatile states are re-derived, not stored, so a start costs the rules for `k` blocks; global format V6 identity binds the protocol magic, Byron and Shelley genesis hashes, and BLAKE2b-256 of exact loaded Alonzo and Conway genesis bytes; V1/V2 identities are incomplete and require explicit operator-confirmed adoption with original-byte backup; the first-block check cannot historically distinguish Shelley-start networks with the same predecessor; no fault injection on RocksDB or the filesystem itself; mainnet-scale memory and throughput not measured (D6)
