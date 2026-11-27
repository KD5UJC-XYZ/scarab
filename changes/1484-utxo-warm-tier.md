### Changed (UTxO store reads, #1484)
- **A warm tier of recently written and often read UTxO entries sits in front of the RocksDB store, within a fixed
  heap budget.** A node whose UTxO set is on disk (`--db`, the RocksDB store) keeps the encodings of the outputs its
  store's writes created most recently, and of the entries read from the store and read again (collateral, reference
  inputs), so that a read of one of them does not wait on the disk; the oldest leave first. `--utxo-warm-mb N` sets the
  budget (by default a sixteenth of the maximum heap, at most 256 MiB; on the heap; 0: no tier), each entry charged its
  encoding and 256 bytes, so the tier's memory does not grow with the UTxO set. The tier holds only the store's current values: every change of the store
  removes the keys it touches before it is made, and an entry read from the store is kept only if no change came
  between its read and its fill. The block path, the read-ahead, the mempool and the forger read through it; local
  state queries' pinned views and the scans read the store. `scarab_utxo_warm_*` metrics give what it holds and its
  hits, and `scarab_utxo_read_ahead_served_total` and `scarab_utxo_read_ahead_stale_total` the read-ahead's.
