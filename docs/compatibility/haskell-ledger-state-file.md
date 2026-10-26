<!-- SPDX-License-Identifier: Apache-2.0 -->
# Haskell ledger-state file (`ledger/<slot>/{state,tables,meta}`)

Part of the [compatibility matrix](../compatibility.md); the register entry is `haskell-ledger-state-file` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

read and written: `HaskellLedgerFile` (the `[1, ...]` envelope, the hard-fork telescope of ledger states and chain-dependent states, `AnnTip`, PBFT/TPraos/Praos states, the Shelley ledger state `[2, ...]` and the `NewEpochState` of every Shelley-based era), `SnapshotMeta` with typed JSON syntax/root/field errors and parser causes (M11-E/#82); incremental table framing, ordered MemPack export and original-byte CRC/metadata verification (Step D S2), authenticated offline Mem snapshot import with staged V5/checkpoint publication (S3), offline atomic reference-layout export from an exact read-only V5 checkpoint (S4)

## Evidence

byte round trip of real files written by a Haskell node 11.1.3: the 40.6 MB Preview Conway `state` (2 snapshots, 1.2 s to read, 640 MB of heap) and a devnet `state` of each era Byron to Conway (`fixtures/haskell-state`), so the pre-Conway TPraos and Babbage shapes are shown against real bytes; typed refusals; derived fields compared with a Scala node snapshot of the same epoch (findings); `SnapshotTablesSuite` and `SnapshotTableStreamSuite`: all six Shelley-based fixture tables, chunk/truncation and metadata faults, 256 KiB stack walks, unsorted-map semantic sinks, and all 4,526,058 preprod outputs converted and streamed to byte-identical table bytes; S3 original-fixture semantic imports, cancellation/ownership/failure regressions and authenticated full preprod V5/checkpoint readback

## Explicitly unchecked or unconfirmed

S4 export preserves the exact state and streams canonical outputs (real preprod byte-identical); offline reference loading/replay, six private epoch boundaries, preprod epoch 320 and the fixed point reported by a synced preprod node are checked (Step D S5, design section 12); ongoing network comparison remains K7; streaming framing alone does not validate map uniqueness: an import must use an era-indexed semantic sink and verify metadata before publication ([design](../design/haskell-snapshots.md) section 9); Byron's ledger state is kept as the original item, not modelled; S3 imports the reference state unchanged with recomputed UTxO bookkeeping, typed map semantics, ancillary authentication and exact local-point/instant-stake checks; Haskell states with a Peras round, a Dijkstra era or the older unversioned snapshot layout are refused; only devnet-size states of the pre-Conway eras; a 32,511,416-byte preprod Conway state round-trips byte for byte (Step D design section 3); mainnet states not read
