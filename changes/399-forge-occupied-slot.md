### Changed (block production, #399)

- When the tip already holds a slot the pool leads, forge an alternative on that block's predecessor (same height), as the reference's `forkBlockForging` does, instead of skipping the slot. Chain selection decides between the two blocks as it does for any slot battle. After a boundary block, the block forged in its slot is its successor, as in the reference. A later slot held, or an immutable block in the slot, is still skipped.
