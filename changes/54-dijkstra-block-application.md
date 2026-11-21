### Added (Leios, #54)

- Under `--reference-build leios-b004fe0c` the node applies Dijkstra blocks on the era rules, as the
  Leios build's `Forker.applyBlock` does: a certifying ranking block's certificate is checked against
  its parent's state, and only then is its endorser block's closure read from the endorser-block
  store and applied before the block is ticked; then Dijkstra's BBODY and Praos2's header rules run,
  in the reference's order. An invalid certificate is an invalid block; a closure missing from the
  store stops the node. A block with a valid proposal that the state's record cannot hold yet (a
  parameter Dijkstra adds), or one the node's own decoders read in two ways, is withheld with no
  verdict and no peer blamed. `follow` no longer refuses the Leios build, but refuses it at start
  without an endorser-block store (`LeiosDbConfig.Backend: InMemory`, or neither `--db` nor `DatabasePath`); the release
  builds keep refusing Dijkstra blocks.

### Fixed (storage, #54)

- A failure of the node's own while the received log is replayed at start (storage, an evaluator or
  provider that cannot judge a block an earlier run selected) stops the start instead of cutting the
  selected chain back and recording a rollback.
