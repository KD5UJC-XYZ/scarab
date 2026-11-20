### Changed (Leios endorser-block store, #830)

- The Leios store holds the reference's two partitions table for table, so that the reference's
  database round-trips exactly: per-partition rows, bodies and closures, the reference's status
  column with its marked value, the immutable partition's own rows (the newest row each copy cycle
  copied), and a closure's allocation apart from its transactions. A store of the previous layout is
  refused; nothing migrates it.
