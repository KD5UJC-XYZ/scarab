### Changed (received block memory, #683, #684)

- Opening the received-block log reads its files one record at a time to rebuild its index and
  keeps no body; its cache of bodies starts empty and holds the bodies received after the start.
  Before, the open read every body of the log into memory at once. The log's format is unchanged.
- With a chain database (`--db`), the block pool holds a received body only until the block's
  admission is processed, when the body is in the received-block log: at most the bodies of the
  admission queue, as the reference keeps a block in memory only while it waits to be added.
  Before, the pool kept the bodies of processed blocks too, bounded by count. Without `--db` the
  pool keeps them as before.
- New gauges `scarab_block_pool_bodies` and `scarab_block_pool_body_bytes` report the bodies the
  pool holds in memory.
