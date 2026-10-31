### Changed (block pool memory, #416)

- With a chain database (`--db`), the blocks the node has processed but not yet made immutable no
  longer keep their bodies in memory: the pool keeps each block's metadata and reads the body back
  from the received-block log when it needs it, as the reference's VolatileDB reads a block from
  its file. Start-up also no longer holds every recovered body at once. A node without `--db` keeps
  the bodies in memory as before.
