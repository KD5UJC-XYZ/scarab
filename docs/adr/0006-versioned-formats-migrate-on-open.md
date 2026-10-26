# 0006. Persisted formats are versioned and migrate on open; never a forced replay

- **Status**: Accepted
- **Date**: 2026-10-15
- **Amended**: 2026-10-19 (#83 greenfield reader retirement/preflight; #159 complete database identity);
  2026-10-23 (#395 RocksDB layouts and UTxO checkpoints)
- **Issue**: [#83](https://github.com/KD5UJC-XYZ/scarab/issues/83)

## Context

A field added to our stake snapshot type changed the snapshot fingerprint and forced a
3-million-block preprod replay. Synced databases are expensive to rebuild (hours to days).

## Decision

Every persisted file kind carries a version (`FormatVersion`, `FileKind`). A build reads the versions
it ~~knows~~ and migrates ~~older files~~ to the current one when it opens them ([amended 2026-10-19](#amendment-2026-10-19-reader-retirement-and-preflight-83):
supported versions only), one file at a time and
atomically (write a temporary file, fsync, rename), logging each migration. ~~An unknown version refuses
the open.~~ ([amended 2026-10-19](#amendment-2026-10-19-reader-retirement-and-preflight-83)) A migration never invents data: what an old file cannot supply is recorded as a gap and
reported, and resyncing is the operator's choice, never forced. Migrations are one way; the docs say
which files to copy to keep an older build working.

## Consequences

- A format change lands with its version bump, its migration and a test that reads the previous version.
- The snapshot fingerprint golden test guards against unversioned changes.
- ~~Old readers are kept (frozen) for as long as migrations from their versions are supported.~~
  ([amended 2026-10-19](#amendment-2026-10-19-reader-retirement-and-preflight-83))

## Amendment (2026-10-18): network identity V6 (#159)

Global version V6 changes only the network identity record: it binds the loaded Byron, Shelley,
Alonzo and Conway genesis inputs. Incomplete V1/V2 identity records require explicit
operator-confirmed adoption and retain their original bytes.

## Amendment (2026-10-19): reader retirement and preflight (#83)

A build reads the versions it supports and migrates supported older files to the current one. An
unknown or known retired version refuses the open before mutable stores are acquired. Historical
identifiers remain registered after their payload readers are retired.

The contributor and operator procedure, including preflight, previous-version evidence, interruption
and reader retirement, is [Storage migrations](../storage-migrations.md). In this greenfield
deployment, retirement follows verified conversion of local and repository inputs and transition of
local writers, rather than an invented future release horizon. Pinned source and archived bridges
keep historical evidence recoverable without retaining unused runtime readers.

## Amendment (2026-10-23): RocksDB layouts and UTxO checkpoints (#395)

RocksDB key/value and column-family layouts and the UTxO checkpoints were persisted layouts with no
version of their own until #395: a snapshot named its checkpoint by directory stem only. Their
admission rule is now this. A checkpoint is admitted only through the snapshot that recorded it:
from global V7 a snapshot carries a `CheckpointBinding` with the `UtxoKeyLayout` and the digest of
the checkpoint directory, and a restore refuses (and falls back from) a checkpoint whose layout or
digest differs, with a typed `StorageError.UtxoCheckpoint`. The live store is never admitted by
itself: each open replaces it by an admitted checkpoint or empties it. A V5 snapshot migrates to V7
with a `Legacy` binding rather than a digest invented at migration time, and is admitted as the V5
build admitted it, with a warning. A new key, value or column-family layout is a new `UtxoKeyLayout`
case, a snapshot version bump and a conversion or refusal of the existing checkpoints; the
historical pre-K6 key conversion (`RocksUtxoStore.rekeyInto`) remains as it was. See
[ADR 0021](0021-ledger-state-residency-and-utxo-checkpoints.md).
