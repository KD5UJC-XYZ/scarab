### Added (the Leios build, #826)

- Under `--reference-build leios-b004fe0c` a Dijkstra state answers `GetCurrentPParams`,
  `GetGovState`, `GetRatifyState` and `GetFuturePParams`, and their `GetCBOR` forms, in that build's
  layout: Dijkstra's 54 parameters and its governance state at version 12, alike at every
  node-to-client version, as that build writes them. `DijkstraGovernanceAnswersSuite` checks them on
  the two Dijkstra states the build wrote against the parts of the state it wrote with the same
  encoders. Under the 11.1 build a Dijkstra state answers none of them, as before; every era up to
  Conway answers as before.
