### Added (offline ledger snapshot import, #140)

- `import-ledger-snapshot` for Shelley–Conway Mem snapshots, with ancillary signature/digest verification by default, exact local immutable-point and derived-stake checks, staged RocksDB/V5 publication, typed refusals and an explicit unauthenticated opt-out. V5 and stored CBOR remain unchanged.
- A shared ownership lease for participating follower/import commands. Container runtimes bind the same stable sibling lease inode when a database is mounted at `/db`.
