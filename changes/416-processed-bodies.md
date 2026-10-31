### Changed (block pool memory, #416)

- With a chain database (`--db`), the pool's forest of processed blocks no longer keeps their
  bodies: it keeps each block's metadata and reads the body back from the received-block log when
  it needs it, as the reference's VolatileDB reads a block from its file. A block the log can no
  longer read leaves the forest and is fetched again. The pool's start-up seeding keeps metadata
  only. The pool's cache of recently received bodies still holds them, bounded by count (#684), and
  opening the log still reads every record once (#683). A node without `--db` keeps the bodies in
  memory as before.
