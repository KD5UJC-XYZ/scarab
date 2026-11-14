### Changed (storage, #824)

- The network identity of a database is format V9 (`scarab network identity v9`): it also binds the
  Dijkstra genesis file the configuration names (its Blake2b-256), or records `none` for a
  configuration without one. A V6 identity is rewritten as V9 at its first open with the configured
  binding, after its other fields are found equal to the configured ones, and keeps its adoption
  record; one of another network is refused and left as it is. An identity that records `none`
  adopts a Dijkstra genesis the configuration names later, once, at the next open; a recorded one
  that differs from the configured one, or that the configuration drops, is refused. The
  `networkRecorded` log event gains the `dijkstraGenesis` and `adoptedDijkstraGenesis` fields.
