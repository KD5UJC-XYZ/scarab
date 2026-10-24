### Changed (storage, #395)

- Ledger snapshots are format V7 (`SCRBSNP7`): the V5 body behind a binding of the UTxO checkpoint
  they were written with (its key layout and a digest of its directory), as the reference's snapshot
  metadata records its backend, checksum and tables codec version. A restore admits a checkpoint
  only when the binding still holds; otherwise the snapshot is skipped with a typed
  `UtxoCheckpoint` error and an older snapshot (or a replay) is used. V5 snapshots migrate on open,
  with the body unchanged and a `Legacy` binding; restoring one logs `snapshotUnbound`. Checkpoint
  pruning deletes only checkpoint-named directories. ADR 0006 is amended and ADR 0021 records the
  layout.
- Received block bodies are read back from the received-block log, as the reference's VolatileDB
  reads blocks from its files; a byte-bounded cache (20,000,000 bytes by default, the reference's
  ten queued blocks of the largest block size) holds the most recent ones, with `scarab_received_*`
  metrics.
