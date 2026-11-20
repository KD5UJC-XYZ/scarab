### Changed (storage, #1247)

- The received log is collected as the reference's VolatileDB: 60 s after each copy of blocks to the
  immutable store, rounded up to a 10 s mark, below the new immutable tip's slot, at most once an
  interval, instead of each time 4,096 blocks have become immutable. A collection that fails stops the
  node.
- The received log's files hold 1,000 blocks, and the next one is started as soon as one fills, so a
  full file can be collected before another block arrives.
- The collection is timed from the end of the copy, before the backing store is brought forward, and
  an exception in the fiber that runs the collections stops the node as a failed collection does.
- Opening the received log validates each file on its own: a file is cut at its first damaged record
  or its first block that fails the start-up check (it does not decode, or its header is not the
  record's), and the files after it are kept, where the files after the first damaged one were
  removed and a malformed block was dropped by itself after the open. A later record of a block
  already indexed, in the same file or a later one, cuts its file there whatever its bytes. A block is
  written to the log once: the start's repair of the selected chain's blocks writes only those the log
  lacks. The selected-chain log's recovery is unchanged.
