### Fixed (network, #602)

- An inbound session's report (`intersection` in the query endpoint's sessions) and its
  `following` event follow every intersection the session's ChainSync client finds, including a
  new one found inside the session after the peer no longer intersects our chain. Before, they
  kept the session's first intersection.
