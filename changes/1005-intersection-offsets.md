### Fixed (chain sync, #1005)

- The node's ChainSync client offers the intersection points the reference offers: the tip, the blocks a
  Fibonacci number of blocks back (1, 2, 3, 5, 8, ...) below the length of our volatile chain, and its anchor,
  which an empty chain offers twice. It used to offer every point up to 64, then the newest 16 and points at
  doubling distances.
