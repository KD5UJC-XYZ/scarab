### Fixed (UTxO store reads of absent keys, #79)

- The RocksDB UTxO store keeps its tables' index and filter blocks with their table readers,
  outside the block cache: a large table's filter no longer fits a shard of the cache, so it was
  read again for every key the store does not hold, about 1.3 ms a key on the public testnet's
  store of 11 million entries. A closure check reads all of a closure's inputs, nearly all made by
  the closure itself and so absent, and took 8 to 11 s for 6,884 transactions; now 1.3 to 3.2 s.
  The index and filter cost about 1.4 bytes an entry beside the cache (16 MB there).
- A batch read tells an absent key from one the database cannot read by each key's status, instead
  of reading every absent key again on its own; a snapshot view's read now fails on a key it cannot
  read, as the direct read does, where it took it for absent.
