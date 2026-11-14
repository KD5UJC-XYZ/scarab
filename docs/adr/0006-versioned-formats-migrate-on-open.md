# 0006. Persisted formats are versioned and migrate on open; never a forced replay

- **Status**: Accepted
- **Date**: 2026-10-15
- **Amended**: 2026-10-19 (#83 greenfield reader retirement/preflight; #159 complete database identity);
  2026-10-23 (#395 RocksDB layouts and UTxO checkpoints); 2026-11-03 (#726 a state that cannot be
  converted without inventing data is refused); 2026-11-14 (formats are frozen until Scarab leaves pre-release)
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
reported, ~~and resyncing is the operator's choice, never forced~~ ([amended 2026-11-03](#amendment-2026-11-03-a-state-that-cannot-be-converted-is-refused-726)). Migrations are one way; the docs say
which files to copy to keep an older build working.

## Consequences

- ~~A format change lands with its version bump, its migration and a test that reads the previous version.~~
  ([amended 2026-11-14](#amendment-2026-11-14-formats-are-frozen-until-scarab-leaves-pre-release))
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

## Amendment (2026-11-03): a state that cannot be converted is refused (#726)

Decided on 2026-11-03 in [#726](https://github.com/KD5UJC-XYZ/scarab/issues/726). Where the current
format holds state that a supported older version discarded, and the node answers from that state
with the reference's bytes, a gap would leave an answer the reference never sends or none at all. A
migration then refuses each older file that held such state, with a typed
`StorageError.ResyncRequired` naming what it lost (`FormatLoss`), before any store opens and before
any file is rewritten; the chain has to be synced again from genesis. A file that held none of it
migrates as before.

The first case is snapshot V8 (`SCRBSNP8`): the Byron update state keeps each software proposal's
installer metadata, which V5 and V7 discarded. A V5 or V7 snapshot whose Byron update state holds a
software proposal or an application version is refused; one that holds neither, every snapshot
taken after the Byron era among them, migrates. Snapshots taken mid-Byron are regenerated by a later
resync; none is repaired by hand. The procedure is in [Storage migrations](../storage-migrations.md).

## Amendment (2026-11-14): formats are frozen until Scarab leaves pre-release

Decided by the owner on 2026-11-14, in the chat session doing the work of this amendment
([decisions register](../decisions.md#genesis-storage-and-keys)). Format changes are suspended until Scarab leaves
pre-release. Deployed stores use V8 only; V9 and V10 are not deployed.

No persisted format changes without the owner's explicit approval, asked for and given in the chat session doing
the work before the change is written: not a version, the snapshot, sidecar or state schema, a fingerprint or magic,
a migration step or a regenerated native snapshot fixture, and not an in-place edit of an existing version, V9 and
V10 included. An approved change is a pull request of its own, and it is what the approval names: a new version
with its migration and a test that reads the previous version, or an in-place edit of a version that no deployed
store uses. The V8 reader and the migration path from V8 stay.

The rule, the record an approved change carries and the guard that refuses an unrecorded change are
[FMT-1](../design-rules.md#fmt-1); the procedure for an approved change is
[Storage migrations](../storage-migrations.md).
