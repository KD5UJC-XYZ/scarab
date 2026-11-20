### Added (Leios chain selection, #54)

- Chain selection parks a ranking block whose Leios certificate references an endorser block whose
  closure is not acquired, hiding it as a candidate tip and as a successor, and reconsiders it when
  the endorser-block store completes the closure, as ouroboros-consensus 2416493b does. The acquired
  set is seeded from the store's complete closures when the chain database opens and pruned below
  the immutable tip. Only blocks of the Leios build's Dijkstra era carry certificates; nothing
  writes the store at runtime yet.
