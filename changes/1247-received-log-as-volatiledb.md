### Changed (storage, #1247)

- The received log is collected as the reference's VolatileDB: 60 s after each copy of blocks to the
  immutable store, rounded up to a 10 s mark, below the new immutable tip's slot, one at a time and at
  most one scheduled for each 10 s mark, instead of each time 4,096 blocks have become immutable. A
  collection that fails stops the node. The copy that schedules it is made for each persisted
  extension, where the reference's copy runner copies the whole eligible prefix (#1262). Each schedule
  and collection is logged at Debug (`scheduledGC`, `performedGC`), as the reference traces them.
- The received log's files hold 1,000 blocks, and the next one is started as soon as one fills, so a
  full file can be collected before another block arrives; files an earlier build wrote keep their
  512-block boundaries, and the last of them, the one being written, is filled to 1,000. The selected-chain log shares the writer: its 512-block files are now rolled
  at the block that fills them, so a full one can be collected at the next 4,096-block trigger before
  another record is written.
- The collection is timed from the end of the copy, before the backing store is brought forward, and
  an exception in the fiber that runs the collections stops the node as a failed collection does.
- Opening the received log validates each file on its own: a file is cut at its first damaged record
  or its first block that fails the start-up check (it does not decode, its header is not the
  record's, or its body does not match its header), and the files after it are kept, where the files
  after the first damaged one were removed and a malformed block was dropped by itself after the
  open. A later record of a block already indexed, in the same file or a later one, cuts its file
  there whatever its bytes. A block the log's index holds is not written again: the start's repair of
  the selected chain's blocks writes only those the log lacks, and a block put again is logged at
  Debug (`receivedBlockAlreadyHere`). Each cut the open makes is logged just before it is made
  (`receivedTruncated`, at Debug, as the reference traces `Truncate`) with its file, offset and cause,
  as the reference's `ParseError` splits it: a record that cannot be read or a block that does not
  decode, a block that decodes and is corrupt, or a block already held in a named file; the open's
  totals are logged as `receivedOpened`. The names of the directory's files the open does not read are
  logged at Debug (`receivedInvalidFileNames`), and in either log a name that reads as a file number
  but is not the name the log writes for it (`1.log` for `00000001.log`) refuses the log before any
  file changes. The selected-chain log still recovers its files in order.
- In either log, a record header whose length runs past the file's end or what an Int holds, or
  whose record fails its checksum, ends the file's readable part without the record being read
  whole, where a damaged length near the largest an Int holds failed the open or asked for an array
  of that size.
