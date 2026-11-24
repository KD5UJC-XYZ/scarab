### Fixed (tooling, #85)

- A `--plutus-cases` case that cannot be written, for example because its directory is full, no longer stops the
  block being applied or hides the cause of a block with no verdict: the node logs `plutusCaseNotWritten` (a
  warning) after the next block it applies and goes on. This holds for every era.
