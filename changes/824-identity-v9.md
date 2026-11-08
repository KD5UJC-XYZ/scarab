### Changed (storage, #824)

- The network identity of a database is format V9 (`scarab network identity v9`): it also binds the
  Dijkstra genesis file the configuration names (its Blake2b-256), or records `none` for a
  configuration without one. A V6 identity is rewritten as V9 at its first open with the configured
  binding, after its other fields are found equal to the configured ones, and keeps its adoption
  record; one of another network is refused and left as it is. The `networkRecorded` log event
  gains a `dijkstraGenesis` field.
