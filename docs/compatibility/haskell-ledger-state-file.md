<!-- SPDX-License-Identifier: Apache-2.0 -->
# Haskell ledger-state file (`ledger/<slot>/{state,tables,meta}`)

Part of the [compatibility matrix](../compatibility.md); the register entry is `haskell-ledger-state-file` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

read and written: `HaskellLedgerFile` (the `[1, ...]` envelope, the hard-fork telescope of ledger states and chain-dependent states, `AnnTip`, PBFT/TPraos/Praos states, the Shelley ledger state `[2, ...]` and the `NewEpochState` of every Shelley-based era), `SnapshotMeta` with typed JSON syntax/root/field errors and parser causes (M11-E/#82); incremental table framing, ordered MemPack export and original-byte CRC/metadata verification (Step D S2), authenticated offline Mem snapshot import with staged V5/checkpoint publication (S3), offline atomic reference-layout export from an exact read-only V5 checkpoint (S4)

## Evidence

byte round trip of real files written by a Haskell node 11.1.3: the 40.6 MB Preview Conway `state` (2 snapshots, 1.2 s to read, 640 MB of heap) and a devnet `state` of each era Byron to Conway (`fixtures/haskell-state`), so the pre-Conway TPraos and Babbage shapes are shown against real bytes; typed refusals; derived fields compared with a Scala node snapshot of the same epoch (findings); `SnapshotTablesSuite` and `SnapshotTableStreamSuite`: all six Shelley-based fixture tables, chunk/truncation and metadata faults, 256 KiB stack walks, unsorted-map semantic sinks, and all 4,526,058 preprod outputs converted and streamed to byte-identical table bytes; S3 original-fixture semantic imports, cancellation/ownership/failure regressions and authenticated full preprod V5/checkpoint readback

Dated evidence (2026-11-13): a Conway state's proposals are read as the reference's
`decSharePlusCBOR` reads them: of two proposals with one id the first is kept, and `mkProposals`
rebuilds the store, refusing a proposal whose previous action is neither its lineage's root nor an
earlier proposal of its lineage (cardano-ledger f649f975, `Conway/Governance/Proposals.hs:305-354,401-406`).
`HaskellStateCodecSuite` pins the refusals with cases built from that definition. A Preview state
written by node 11.1.3 at slot 125,418,230 (`state` sha256
`538c1e333ecd382f2cde2cae114cf8141e64830c55e34bd4dcb9932b1284522a`), whose 34 proposals include 14 of a
lineage, each directly under its lineage's root, is still read and written again byte for byte
(`LedgerFileSuite`, `HASKELL_STATE_FILE`). That state holds no proposal under an earlier proposal,
so that case rests on the definition. The raw state is not published. The state section of a native
snapshot is read with its proposals as written (ADR 0006): a snapshot holding a store the rebuild
refuses is restored as it was written, not deleted (`SnapshotFallbackSuite`).

## Explicitly unchecked or unconfirmed

S4 export preserves the exact state and streams canonical outputs (real preprod byte-identical); offline reference loading/replay, six private epoch boundaries, preprod epoch 320 and the fixed point reported by a synced preprod node are checked (Step D S5, design section 12); ongoing network comparison remains K7; streaming framing alone does not validate map uniqueness: an import must use an era-indexed semantic sink and verify metadata before publication ([design](../design/haskell-snapshots.md) section 9); Byron's ledger state is kept as the original item, not modelled; S3 imports the reference state unchanged with recomputed UTxO bookkeeping, typed map semantics, ancillary authentication and exact local-point/instant-stake checks; Haskell states of a Dijkstra era or the older unversioned snapshot layout are refused (a hard-fork `Bound` with a Peras round is read, as the 11.1 reference reads it, #826); only devnet-size states of the pre-Conway eras; a 32,511,416-byte preprod Conway state round-trips byte for byte (Step D design section 3); mainnet states not read
