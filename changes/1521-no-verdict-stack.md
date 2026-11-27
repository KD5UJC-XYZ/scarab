### Added (the stack trace of a rules defect, #1521)

- The first `blockNoVerdict` line of a block the rules threw on (kind `defect`) has a `stack` field: the
  throwable's stack trace and its causes as lines, at most 64 frames for each of at most 8 throwables. The
  retries of the same block log the reason alone, as before. The line logged for the mainnet block at slot
  120,650,699 named a `NullPointerException` and no frame, so where the rules threw could not be found.
