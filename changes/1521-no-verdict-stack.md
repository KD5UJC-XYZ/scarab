### Added (the stack trace of a rules defect, #1521)

- When the first judgement of a block (attempt 1) ends in a throwable of the rules (kind `defect`), its
  `blockNoVerdict` line has a `stack` field: the
  throwable's stack trace and its causes as lines, at most 64 frames for each of at most 8 throwables. The
  retries of the same block log the reason alone, as before. The line logged for the mainnet block at slot
  120,650,699 named a `NullPointerException` and no frame, so where the rules threw could not be found.
