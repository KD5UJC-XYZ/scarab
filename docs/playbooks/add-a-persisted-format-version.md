# Add a persisted-format version

Level 3. A format change needs a version bump, a migration and a test that reads the previous version ([ADR 0006](../adr/0006-versioned-formats-migrate-on-open.md)). The procedure with all conditions is [storage-migrations.md](../storage-migrations.md); this is the order of work.

1. Decide the kind: each file family is a `FileKind` in `ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/PersistedFormat.scala` with a version history and a readable-from version. RocksDB layouts are not `FileKind` values; do not imply a bump versions them.
2. Append the version to that kind's history (a new identifier, for example the next `SCRBSNP` magic). Keep the previous identifier registered.
3. Write the migration step in the kind's registered migration (`Migrate`, `SnapshotMigration`; steps are `step(file, from, to)`): verify the old checksum, read with the frozen previous reader, convert the typed state, write atomically (`AtomicFile.replace`). Carry gaps and observed eras exactly; never invent data an old file did not hold.
4. Tests: a fixture of the previous version read and migrated (`FormatMigrationSuite`), idempotence (a second migration changes no bytes), interruption, and the retired/unknown refusals. Update the fingerprint golden in `node/src/test/scala/xyz/kd5ujc/scarab/storage/CodecSuite.scala` together with the version. Regenerating a fingerprint to pass a test is not a migration.
5. Preflight: `ChainStorage.preflight` must accept or refuse the new version before any store is opened; keep network-mismatch precedence.
6. Maintained fixtures and builders use the current version; assert typed state, not a flat adapter.
7. Gates (`sbtw` is the Docker wrapper of [build in Docker](build-in-docker-from-a-worktree.md)):

```sh
sbtw check
python3 scripts/devnet.py --name mine restart
python3 scripts/devnet.py --name mine down
```

8. Paperwork: `changes/<issue>-<slug>.md`, the compatibility row, the design's "as built", the roadmap line, and the migration notes in [storage-migrations.md](../storage-migrations.md). Retire an old reader only after its conditions there hold.
