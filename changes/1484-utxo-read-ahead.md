### Changed (UTxO store reads, #1484)
- **The store reads of the blocks waiting in the add-block queue are issued ahead of the worker.** A node whose UTxO set
  is on disk (`--db`, the RocksDB store) reads each queued block's inputs that its tip does not decide while earlier
  blocks are applied, several blocks at once, into a buffer bounded in entries and bytes; the worker takes what was
  read ahead and reads only the rest. On a hard disk the block-selection worker had spent a third of its time waiting
  on these reads, one block after another. The entries a block is applied with are the store's, as before: an entry is
  used only where the state it is read for shows it cannot have changed since it was read, and a reset or restore of
  the store forgets what was read before it. Local state queries, the mempool and the forger read the store directly.
