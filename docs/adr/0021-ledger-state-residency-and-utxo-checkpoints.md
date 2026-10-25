# 0021. Ledger state residency and the UTxO checkpoint layout

- **Status**: Accepted
- **Date**: 2026-10-23
- **Issue**: [#395](https://github.com/KD5UJC-XYZ/scarab/issues/395)

Number note: 0020 is claimed by the open PR #413 (Scalus crypto JNI), so this record takes 0021.

## Context

The node keeps its ledger state in two places. The snapshot file holds everything but the UTxO
entries: the Haskell ledger state with `map(0)` for the tables and a sidecar with the UTxO set's
bookkeeping (`SnapshotV5`). The entries live in RocksDB: a live store that follows the immutable
tip, and a checkpoint of it taken at each snapshot's anchor under
`<db>/utxo/checkpoints/snapshot-<slot>-<blockNo>`. Until V7 a snapshot named its checkpoint only by
that directory stem; nothing recorded which checkpoint, or which key layout, it was written with,
and `UtxoBacking.retain` deleted every directory not named by a listed snapshot.

The reference records the same association in the metadata of each LedgerDB snapshot: the
backend, a checksum and the tables codec version (`SnapshotMetadata`, `TablesCodecVersion`,
Ouroboros/Consensus/Storage/LedgerDB/Snapshots.hs:206-227 at `release-ouroboros-consensus-4.2.1.0`),
checked before the snapshot is used.

Received block bodies are a second residency question. The reference's VolatileDB keeps an index
of every block in memory (file, offset, size: `InternalBlockInfo`, VolatileDB/Impl/Types.hs:127-133)
and reads a body from its file when asked (`getBlockComponentImpl`, VolatileDB/Impl.hs:259-306); a
received block is held in memory only while it waits in the queue of blocks to add, of
`cdbsBlocksToAddSize = 10` blocks (ChainDB/Impl/Args.hs:71-75 and 122). Scarab's `ReceivedBlocks`
kept every received body in heap until collection, which runs every 4,096 immutable blocks.

## Decision

**Snapshots bind their checkpoint (snapshot format V7).** A V7 file is
`SCRBSNP7 | binding | V5 body`, the binding being a `CheckpointBinding` with its own schema
fingerprint:

- `Bound(layout, digest)`: the `UtxoKeyLayout` of the checkpoint and the SHA-256 digest of its
  directory (`CheckpointDigest`): every data file in name order, by name and size, and by content
  except the table files (`*.sst`, `*.blob`), whose number and size the hashed `MANIFEST` records.
  RocksDB's diagnostics (`LOG`, `LOG.old.*`) and `LOCK` are left out, since opening a checkpoint
  read-only may create them. The digest reads only small files, so it stays cheap for a UTxO set of
  any size; it is taken right after the checkpoint and checked before the snapshot is restored.
- `Unbacked`: the state keeps nothing outside the file (`StateBacking.none`).
- `Legacy`: the file was migrated from V5, which recorded nothing.

On restore `StateBacking.admit` refuses a snapshot whose checkpoint directory is absent, whose
layout is not this build's, or whose directory digest differs from the recorded one, with a typed
`StorageError.UtxoCheckpoint(dir, CheckpointProblem)`. The snapshot store skips it, as it skips any
unusable snapshot, and restores the next older one or replays. The offline export and SCLS base
readers apply the same admission before they open a checkpoint.

**V5 files migrate on open to `Legacy`, not to a digest of what is there now.** The step
(`SnapshotV7.fromV5`) copies the V5 body byte for byte behind a `Legacy` binding. Binding the
directory found at the stem during migration would certify a pairing the migration never saw
written; ADR 0006 says a migration never invents data. A `Legacy` snapshot is admitted when its
directory exists, as the V5 build admitted it, and its restore is reported with the
`snapshotUnbound` warning (`scarab_snapshot_problems_total{kind="Unbound"}`). Newer snapshots replace
it after `keepSnapshots` writes. The reference refuses a snapshot without metadata
(`ReadSnapshotIsLegacy`, Snapshots.hs:202-203); Scarab admits it instead, because ADR 0006 rules out a
forced replay of a database a previous build wrote and the V5 trust is exactly what that build
already relied on.

**Only checkpoint-named directories are pruned.** `retain` deletes a directory only when its name
is a snapshot stem (`StateBacking.isStem`) that no listed snapshot uses.

**The live store is derived.** At open the live store is either replaced by an admitted checkpoint
(`restore`) or emptied (`reset`); it is never admitted from disk by itself, so its layout needs no
admission rule of its own beyond the checkpoint's.

**Received bodies are read back from their log.** `ReceivedBlocks` keeps the index (reference and
`VolatileStore.Location`) in memory and the bodies in its log; a body is read back with its record
checksum checked. In front of the log is a cache of the most recently received bodies bounded in
bytes, oldest evicted first. The default bound is 20,000,000 bytes: the reference's 10 queued
blocks times the largest block any era allows, the Byron `maxBlockSize` of 2,000,000 bytes. A record
that fails its checksum on read back is dropped from the index (the block is fetched again); a file
system failure is a storage error. The residency is exported as `scarab_received_*` metrics.

## Consequences

- The snapshot kind is at V7 (`SCRBSNP7`); V5 stays readable through its migration, with the V5
  sidecar golden and the new binding golden in `CodecSuite`, and the V5 fixtures as the
  previous-version inputs of `fixtures/snapshot-v7`.
- A checkpoint changed after its snapshot was written (another database's directory under the same
  stem, a copy taken at another time, a damaged or partial copy) costs an older snapshot or a
  replay, never a restore of the wrong entries.
- A future UTxO key or value layout, or a move to column families, is a new `UtxoKeyLayout` case,
  a new snapshot version and a conversion or refusal of the checkpoints (ADR 0006).
- Only the received index's own bodies are bounded. `BlockPool` keeps references to the bodies of
  the blocks it has processed until immutable-slot pruning; reading those through the index is
  [#416](https://github.com/KD5UJC-XYZ/scarab/issues/416).
- Opening the received log still reads its files whole once to validate them, as before.
