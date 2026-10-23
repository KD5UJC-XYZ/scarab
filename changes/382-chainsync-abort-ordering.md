### Fixed (ChainSync pipeline, #382)

- The pipelined ChainSync client publishes a receiver-side failure only after its abort callback has
  completed, so a collector or later call that sees the typed failure also sees the bearer aborted. Before,
  the failure was published first and the abort ran after, which let a caller observe the failure with the
  abort still pending (intermittent `ChainSyncPipelineSuite` failures on main).
