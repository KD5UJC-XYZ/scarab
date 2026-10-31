### Fixed (state dump at epoch boundaries, #442)

- `--dump-state epoch-boundaries` decides an epoch boundary from the state each block was applied
  to and the state it produced, not from the last block it saw. A block that replaces a rolled-back
  boundary block now gets its digest, so the devnet oracle can compare that boundary. The first
  block after a restart is a boundary when it crosses into a new epoch. The block that ends Byron is
  still not a boundary.
