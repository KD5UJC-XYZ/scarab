<!-- SPDX-License-Identifier: Apache-2.0 -->
# Persistence

Part of [Architecture](../architecture.md).

## Persistence (`follow --db <dir>`)

Modelled on the ouroboros-consensus ChainDB, as tagless-final algebras in the `storage` module (`ChainStorage` is the
composition and `Follower` the consumer, both in `node`):

| Part | Where | Role |
|---|---|---|
| `ImmutableStore[F]` | `ImmutableStore.scala` (`FileImmutableStore`) | Append-only raw blocks older than `k`: `append`, `sync`, `tip`, `get(point)`, `byBlockNo`, `stream(after)` |
| `LedgerSnapshots[F, S]` | `LedgerSnapshots.scala` (`FileLedgerSnapshots`) | Atomic, checksummed snapshots of the full `MirrorState` at an immutable point; newest valid wins. The file's content is a `SnapshotFormat[F, S]` (`SnapshotV7`: a `CheckpointBinding`, then the Haskell ledger state and a `SnapshotSidecar`); a write refusal is a value (`SnapshotRefusal`), while retired/unknown reads refuse restoration |
| `SnapshotMigration[F]` | `SnapshotMigration.scala` | Registered migration structure and read-only version admission; historical V1–V4 payloads are retired, V5 is current |
| `VolatileStore[F]` | `VolatileStore.scala` | Append-only log of the volatile chain's updates (blocks, rollback markers); lets a restart continue at the previous tip |
| `NetworkIdentityFile` | `NetworkIdentityFile.scala`, `NetworkIdentity.scala` | The network the database was created for (`<db>/network-identity`), checked on every open |
| `SnapshotPolicy` | `SnapshotPolicy.scala` | When the immutable tip is snapshotted: `Aligned` (the Haskell node's default, a multiple of 40 k slots and at most one per 10 minutes) or `EverySlots` (`--snapshot-every`); the pure `due` |
| `ChainStorage` | `ChainStorage.scala` | Checks the identity, opens the stores, restores the newest usable snapshot, replays the stored blocks after it, rebuilds the volatile chain (`resume`), hands the follower a `ChainPersistence` hook, and snapshots the immutable tip when it is released |
| `Codec[A]` | `Codec.scala`, `NodeCodecs.scala` | Binary codecs, one per type, composed. Case classes and enums are derived from their `Mirror`, so a field added to a persisted type is persisted with no codec change |
| `StorageError` | `NodeError.scala` | `Corrupt`, `Io`, `VersionMismatch`, `Missing`, `OutOfOrder`, `NetworkMismatch`, `ForeignChain`; raised through `Raise[F, StorageError]`, IO exceptions are mapped at the file boundary only |
| `HaskellImmutableReader[F]` | `storage/haskell/` | Read-only view of a Haskell node's ImmutableDB (see below); `HaskellImport` appends its blocks to our `ImmutableStore` |

```sh
follow --genesis-dir <dir> --peer <host>:<port> --db <dir> [--snapshot-every <N|Nslots|Nepochs>] [--keep-snapshots N]
```

Without `--db` nothing changes: the node is in-memory and replays from genesis. With `--db` the
JSON log gains `immutableOpened`, `snapshotRestored`, `snapshotSkipped`, `snapshotWritten`,
`snapshotFailed`, `replayStarted`, `replayProgress`, `replayFinished`, `networkRecorded`,
`volatileOpened`, `volatileResumed`, `shutdownSnapshot`, `snapshotRefused` (`file`, `anchor`, `reason`: the
format refused the state, nothing was written) and, the first time an older database is opened,
`formatMigrated` (`kind`, `from`, `to`, `count`; see "Format versions and migration").

**Immutable store format** (`<db>/immutable/`). `format` names the version and chunk size
(`chunkSlots`, 21600); a different chunk size is a `VersionMismatch`. Blocks of slots
`[c * chunkSlots, (c + 1) * chunkSlots)` go to `<c>.chunk` (concatenated raw blocks) and
`<c>.index`: the magic `SCRBIDX2` (`CSLIDX01` in format 1) then 68-byte big-endian entries
`offset u64 | length u32 | crc32 u32 | slot u64 | blockNo u64 | hash[32] | flags u8 | zero[3]`
(`flags` bit 0 marks a Byron epoch-boundary block). Appends write data then index without syncing;
`sync` (before every snapshot, at chunk roll-over and on close) forces both and the directory.
On open the newest chunk is validated entry by entry (contiguous offsets, data in bounds, CRC) and
both files are truncated to the last good entry; a chunk left with no good entry is removed, and
data files without an index are deleted. Older chunks were synced at roll-over and are trusted.

**Haskell ImmutableDB reader** (`storage/.../haskell/`, reader only; our store keeps its own
format, and writing the Haskell layout is not implemented: it belongs to the Haskell-format adoption of M11). `HaskellImmutableDb.scala` holds the pure parts: the opaque `ChunkNo`,
`ChunkSlots` (10 x k of the first era: 4,320 on Preview, 21,600 on mainnet and preprod) and
`ByteOffset`; `PrimaryIndex` (version byte 1, big-endian `u32` offsets, one per relative slot plus
one, a step of 56 for a filled slot; relative slot 0 is the EBB slot) and `SecondaryIndex` (56-byte
entries: block offset u64, header offset u16, header size u16, CRC32 u32, header hash, slot or, for an
EBB, epoch u64); `ChunkValidator` (offsets contiguous, block sizes from the next offset or, for the
last block, from the CBOR item length, CRC32 of the block bytes, the Blake2b-256 of the header slice
equal to the indexed hash for Shelley-based blocks, the slot equal to the one the primary index
position implies); and the closed `ChunkFault` and `HaskellImmutableError` ADTs.
`HaskellImmutableReader[F]` lists the chunks (they must be contiguous from 0), and `contents()` /
`blocks()` stream `HaskellBlock(chunk, position, slot, hash, bytes)` in chain order. Every chunk but
the newest must validate completely; the newest is cut back to its valid prefix, as the Haskell node
does with `ValidateMostRecentChunk`, and what was ignored is reported (`ChunkContents.dropped`)
because the node writes the chunk file before its two indexes. The reader never writes. Our chunk `c`
(21,600 slots) holds the blocks of Haskell chunks `5c` to `5c + 4` byte for byte on Preview: the block
bytes are the same `[era tag, block]` items. `import-haskell-db` (`HaskellImport`) additionally
decodes every block, checks its header hash and slot against the index, the predecessor hash and the
block numbering, the protocol magic (`protocolMagicId`) against the configured network and the first
block's predecessor against the configured genesis, then appends to the `ImmutableStore`; it
continues after the tip of a non-empty target. Epoch-boundary blocks (Byron) are covered by tests
built from the upstream encoding rules (`fixtures/eras`); no Byron Haskell database was available.

**Snapshot format** (`<db>/snapshots/snapshot-<slot>-<blockNo>.bin`, the newest `--keep-snapshots`,
default 2, are kept). A file is the content of a `SnapshotFormat` and the SHA-256 of that content;
the store (`FileLedgerSnapshots`) does the framing, the atomic write and the pruning, and the format
decides what the content is. Format version 7 (`SnapshotV7`, #395) is the body of format version 5
(`SnapshotV5`, J2.9; versions 3 and 4, `SCRBSNP3` and `SCRBSNP4`, had the same layout and sidecars that held the node's chain-dependent state in its former shape and, in version 4, the old rules' flat state) behind the binding of the UTxO checkpoint the snapshot was written with:

```
"SCRBSNP7" | binding length u32 | binding | sidecar length u32 | sidecar | Haskell state bytes | SHA-256 of all before
binding = schema fingerprint[8] | Codec of CheckpointBinding
sidecar = schema fingerprint[8] | Codec of SnapshotSidecar
```

A V5 file is the same from `sidecar length` on, under the magic `SCRBSNP5`. The binding
(`CheckpointBinding.Bound`) holds the key layout of the checkpoint and the digest of its directory,
and a restore admits the checkpoint only when both still hold (`StateBacking.admit`,
[ADR 0021](../adr/0021-ledger-state-residency-and-utxo-checkpoints.md)); a V5 file migrates to V7 with
the `Legacy` binding and is restored with a `snapshotUnbound` warning.

The Haskell state bytes are a `state` file of a Haskell node (`[1, [ledger state telescope, header
state]]`, `HaskellLedgerFile`) of the chain's state `MirrorState.ext`, byte-proven against real files;
the UTxO in it is `map(0)`, as in the reference's `state` file, and its entries stay in the RocksDB
store under the checkpoint of the snapshot. The sidecar (`SnapshotSidecar`, derived `Codec`s, a
fingerprint of its own) holds what the Haskell encoding has no place for: the anchor, the eras the node
saw begin where the configuration does not say, the gaps of the mirror, the node's `Utxo` bookkeeping
(stake by credential and pointer, count, total, stamps, diff), the node's full protocol parameters (they
have every field in every era) and the extras of the mirror (the bootstrap and Byron chain-follow state,
the epoch of the last tick, the TPraos previous epoch nonce, the Plutus statistics). A chain still in
Byron has no Haskell section (the mirror does not model the Byron ledger state): the sidecar holds the
chain-dependent state Shelley starts from and the consensus bookkeeping, the Byron ledger among it. The
chain's state is the mirror itself (`MirrorState`), so the read is `HaskellLedgerFile.decode`, the UTxO
put back and the whole parameter sets put back (the future ones laid over with what the update changed,
`ProtocolParams.overlay`) and nothing converted: the telescopes, the tip and the new-epoch state are
those of the file. A write reads its own content back and refuses (`snapshotRefused`, the snapshot is
skipped, nothing is pruned) what would not restore to that state, and the full check
(`--snapshot-check full`) writes what it read again and compares the bytes: a snapshot is an
optimisation, so a refusal costs a replay at worst. An empty relay list of a pool is `Bytes.empty` after
a read (the Haskell encoding writes both the item as given and none as the empty array).

Versions 1–4 are known retired formats. Their payload readers were used by the historical migration
build at `648aed117f826d06117f616b5c0a3716a50485a7`; verified current V5 replacements and conversion
provenance are the maintained inputs. Historical source remains recoverable for offline conversion.
The runtime retains their identifiers to raise `RetiredVersion` rather than treating them as corrupt
snapshots and silently replaying. [Storage migrations](../storage-migrations.md) describes the conversion
and retirement conditions, and how to add a future migration without inventing state.

Historically, format version 2 (`legacy.SnapshotV2`, magic `CSLSNAP1`) was
`"CSLSNAP1" | format number u32 | schema fingerprint[8] | payload | SHA-256`, the payload the codec of
`StateSnapshot(anchor, NodeState)`; integers are LEB128 varints, `BigInt` is length-prefixed two's
complement, containers are a count then the elements, sums a variant index then the variant. The
fingerprint is the SHA-256 prefix of the codec's `shape` (the names and types of every persisted
field), so changing any state type makes older snapshots fail with `VersionMismatch` rather than
decode wrongly. Version 1 differed in the number and the fingerprint only (written before the
packages were renamed). A write goes to `*.tmp`, is fsynced, renamed (atomic) and the directory is
fsynced; leftover `*.tmp` files are removed on open. Snapshots are written by a background fiber
from the immutable (structurally shared) anchor state, so following does not pause.

**Recovery.** Snapshots are tried newest first. One that fails its checksum, decodes badly, has
another schema or names an anchor missing from the immutable store is reported (`snapshotSkipped`)
and the next older one is tried; with none usable the stored blocks are replayed from genesis. The
stored blocks after the chosen snapshot are re-applied through the same `MirrorRules` with no
network, then ChainSync resumes with the replayed anchor as the intersection candidate.

**Volatile blocks** (`<db>/volatile/<seq>.log`). The last `k` blocks are kept in memory with their
states, and every update of that chain is also appended to a log, the ouroboros-consensus VolatileDB
as an append-only file instead of a block store with a successor map: a record is
`crc u32 | kind u8 | flags u8 | length u32 | slot u64 | blockNo u64 | hash[32] | payload`, the CRC-32
covering everything after it. `kind` is a block (payload: the raw block) or a rollback to a point
(Origin or a block; no payload), so the chain a log describes is a fold over its records
(`VolatileStore.chainOf`) and forks need no extra structure. A file rolls over after 512 blocks.
The store is a hint, not a source of truth, and is built so that it can only be wrong by missing
blocks:

- Opening validates the files in order and cuts the first damaged one at its last good record
  (`volatileOpened` reports the bytes and the files removed); a record is never returned unless its
  checksum matches. Appends are not forced one by one: a crash loses at most the unforced tail, and
  those blocks are fetched again.
- Collection never loses a block the immutable store lacks. Blocks reach the immutable store when
  they age past `k`; every 512 of those the immutable store is forced (`sync`) and the log files
  whose blocks are all at or below the new immutable tip are deleted (oldest first, the newest file
  stays). A rollback marker whose target was collected leaves an empty chain, which is where the
  volatile chain starts after the immutable tip anyway.
- On start, after the snapshot is restored and the immutable blocks replayed, `ChainStorage.resume`
  takes the logged chain after the immutable tip (at most `k` blocks) and applies each block through
  the node's rules on top of the recovered state (`Verification.warm` first), rebuilding the exact
  `VolatileChain`. A block that does not connect or that the rules refuse ends the chain there; the
  log is cut back with a rollback marker and the rest is fetched from the peer (`volatileResumed`
  reports `resumed` and `dropped`). The relay's served chain and the mempool start from that tip, so
  the first ChainSync intersection offered to the peer is the previous tip, not the immutable
  boundary. The states are not stored (they are a pure function of the blocks), only re-derived.

**Shutdown.** Releasing the storage resource (SIGTERM or SIGINT through the IOApp's cancellation,
`--seconds`, or a fatal error) first waits for a snapshot in flight and then, unless the newest
snapshot already is at the immutable tip, forces the immutable store and writes a snapshot with its
UTxO checkpoint at the tip (`shutdownSnapshot`). The stores are released after that. Persisting one
block (log, pending list, immutable append, backing store, tip bookkeeping) is uncancelable, so
cooperative cancellation waits for that sequence. This is not a transaction across the stores;
storage failure and abrupt process termination still require recovery. After a successful shutdown
snapshot, the next start restores it, replays no immutable block
(`replayFinished` with `replayed` 0) and resumes the volatile chain from the log. After `kill -9`
nothing of this runs: the start restores the newest periodic snapshot, replays the stored blocks after
it, and resumes the volatile chain from whatever the log holds.

**Format versions and migration.** The files whose first bytes name their format are the network
identity, the immutable store's `format` file, every chunk `.index` and every ledger snapshot. Their
identifiers, per version (`FileKind.identifier`, `ledger/.../PersistedFormat.scala`); a kind has a
new identifier only at a version at which its layout changed, and `FileKind.current` is the newest:

| file | format 1 (until the rename) | format 2 | format 3 | format 4 | format 5 | format 6 | format 7 |
|---|---|---|---|---|---|---|---|
| `network-identity`, first line | `cardano-scala-lab network identity v1` | `scarab network identity v2` | (as 2) | (as 2) | (as 2) | `scarab network identity v6` | (as 6) |
| `immutable/format`, before ` chunkSlots=<n>` | `cardano-scala-lab immutable store v1` | `scarab immutable store v2` | (as 2) | (as 2) | (as 2) | (as 2) | (as 2) |
| `immutable/<c>.index`, 8-byte magic | `CSLIDX01` | `SCRBIDX2` | (as 2) | (as 2) | (as 2) | (as 2) | (as 2) |
| `snapshots/snapshot-*.bin`, 8-byte magic | `CSLSNAP1`, number 1 | `CSLSNAP1`, number 2 | `SCRBSNP3` | `SCRBSNP4` | `SCRBSNP5` | (as 5) | `SCRBSNP7` |

The immutable format and index layouts are unchanged after the V2 rename. V6 extends the identity
record with Alonzo/Conway bindings and adoption provenance. Formats 3 to 5 are the snapshot as the
Haskell ledger state plus a sidecar (see
"Snapshot format"; 4 and 5 changed the sidecar), and format 7 puts the checkpoint binding in front
of the V5 body. The versions are a closed `enum FormatVersion` (a version this build does not
know cannot be represented) and each kind of file is a `FileKind` with its history of identifiers.
On open, `Migrate` (storage) runs a `Migration[F]` over the files of a kind: it reads the version of
every file first (so an unknown version raises `StorageError.UnknownVersion` and nothing has been
rewritten), then steps each stale file to the next version until it is at the current version of its
kind (or directly to the current version when a registered converter supports that transition).
Snapshot V1–V4 conversion is historical tooling recovered from pinned source, not a migration
offered by the current runtime. A kind unchanged at global V6 is not rewritten, so a V6 identity update
does not touch the 29,031 chunk indexes of a Preview database. A step is `AtomicFile.replace`: a
temporary file in the same directory, fsync, rename
over the original, fsync of the directory; so a crash leaves every file whole at one version, and the
next open finishes. The chunk indexes are migrated first and the `format` file last. Each kind
migrated is reported once (`formatMigrated`); the second open reports nothing. Reading accepts each
kind's supported versions (`readableFrom` through `current`); old identity/index spellings remain
supported, while snapshot V1–V4 identifiers are recognized as retired. The per-entry index read path
never looks at the magic. The migration is one way: an older build does not
know the newer formats (keep a copy of a database to run an older build on it).

`SnapshotMigration` retains the generic `Migration` / `Migrate` registration for versioned
conversions. V5 and V7 snapshot payloads are supported: V5 files are migrated to V7 on open
(`SnapshotV7.fromV5`, the body copied unchanged behind a `Legacy` binding). A read-only identity verification
precedes snapshot version preflight; `Main` holds database ownership and performs this before opening
RocksDB, and `ChainStorage.open` repeats it before identity migration or immutable/volatile recovery.
A mismatch of network identity takes precedence. Retired and unknown formats refuse admission without
rewriting another file or resetting backing state. Direct migration entry points verify support too;
`step` probes the real file before accepting a caller's claimed current version. Corrupt current files
retain their existing recovery policy. Generic `FileLedgerSnapshots.open` tidies temporary files, so
callers needing the full no-mutation admission guarantee first use `ChainStorage.preflight`; direct
restore propagates typed incompatible-version errors rather than skipping them. The RocksDB
checkpoint-key conversion remains independent. `scripts/oracle/export.py` still reads both index
magics.

**Snapshot reader retirement (D10).** Current data uses V7 (V5 migrates to it); historical V1–V4 identifiers stay in
`FileKind` so startup can distinguish a known retired format from an unknown one. Retirement requires
verified current-format replacements for every active local and repository input and transition of
local old writers. Originals and pinned historical conversion evidence remain recoverable outside
the runtime reader path. A separately pinned older deployment keeps its compatible binary until an
explicit upgrade converts and verifies its final database copy; source landing does not perform that
operational transition (#37/#115 for preprod). The procedure and required checks are in
[Storage migrations](../storage-migrations.md); genesis conversion (#134) and RocksDB checkpoint-key
migration have separate dependencies.

**Network identity** (`<db>/network-identity`). A plain-text file, written atomically when the
database is created. V6 is complete; V1 and V2 are retained as explicitly incomplete legacy
records:

```
scarab network identity v6
magic 2
shelley-genesis <blake2b-256 of the Shelley genesis file as read>
byron-genesis <hash, or none for a chain that starts in Shelley>
alonzo-genesis file:<blake2b-256 of the exact loaded file bytes>
conway-genesis file:<blake2b-256 of the exact loaded file bytes>
provenance fresh
```

`ChainStorage.open` compares the complete record with the identity built from the files node setup
loaded; a mismatch raises `StorageError.NetworkMismatch` (use the matching `--genesis-dir` or
another `--db`). V1/V2 records do not contain Alonzo or Conway bindings and are never upgraded
automatically. A populated legacy identity or an identity-less database requires the explicit
`--adopt-legacy-network-identity` option. Before it writes a V6 identity, the node checks every
known legacy field, checks the first immutable block's predecessor when available, and preflights
snapshot and immutable-format headers read-only. The original legacy identity bytes are retained
beside the new file; V6 records operator confirmation separately from identity equality and does
not claim that adoption proves the database's historical genesis. A Shelley-start chain's first
block has no Byron predecessor, so that check cannot distinguish historical Shelley-start networks.
Fresh databases receive V6 immediately. Global V6 changes no snapshot or immutable layout, so
unchanged file kinds are not rewritten.

Tests (`storage/src/test/.../storage`, and `ChainStorageSuite` in `node/src/test`): round-trip properties for every persisted type from generators
derived by the same `Mirror`s (so new fields are covered automatically), prefix/trailing/random-byte
rejection, golden encodings and a golden digest and schema fingerprint, the store's crash cases
(torn data, torn index entry, garbage, flipped bytes, lost chunk, every cut point of the newest
chunk), snapshot corruption at many points and fallback, and restart equivalence over the Byron to
Conway capture (`fixtures/eras`) through the real follower. `VolatileStoreSuite` checks the log against
its pure model over random appends, rollbacks, collections and reopens, every crash cut point of the
newest file, and damage in an older file. `ChainStorageSuite` adds restart equivalence with and
without the volatile log, a graceful stop followed by a start that replays 0 blocks, copies of the
database taken at 12 points of a run (standing in for a crash there) that all restart to the same
final state, the volatile log torn at every record boundary, a logged block that does not apply, and
the identity cases (created, reopened, four kinds of mismatch, adoption, refusal).
