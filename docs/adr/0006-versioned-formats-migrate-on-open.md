# 0006. Persisted formats are versioned and migrate on open; never a forced replay

- **Status**: Accepted
- **Date**: 2026-10-15
- **Amended**: 2026-10-18 (#159 complete database identity)
- **Issue**: [#83](https://github.com/KD5UJC-XYZ/scarab/issues/83)

## Context

A field added to our stake snapshot type changed the snapshot fingerprint and forced a
3-million-block preprod replay. Synced databases are expensive to rebuild (hours to days).

## Decision

Every persisted file kind carries a version (`FormatVersion`, `FileKind`). A build reads the versions
it knows and migrates older files to the current one when it opens them, one file at a time and
atomically (write a temporary file, fsync, rename), logging each migration. An unknown version refuses
the open. A migration never invents data: what an old file cannot supply is recorded as a gap and
reported, and resyncing is the operator's choice, never forced. Migrations are one way; the docs say
which files to copy to keep an older build working. Global version V6 changes only the network
identity record: it binds the loaded Byron, Shelley, Alonzo and Conway genesis inputs. Incomplete V1/V2
identity records require explicit operator-confirmed adoption and retain their original bytes.

## Consequences

- A format change lands with its version bump, its migration and a test that reads the previous version.
- The snapshot fingerprint golden test guards against unversioned changes.
- Old readers are kept (frozen) for as long as migrations from their versions are supported.
