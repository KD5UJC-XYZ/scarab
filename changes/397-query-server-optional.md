### Changed (query server, #397)
- **`--query-listen off|<port>`** turns the loopback HTTP query server off; every route, `/connections` included, goes with it
  (`--metrics-listen` still serves `/metrics`). `--query-port P` remains and means `--query-listen P`; giving both is refused.
  The default is unchanged.
- **`GET /state` is a summary by default**: `utxoSummary` (entry count and lovelace) instead of the entries. `utxo=full` is the
  explicit opt-in and is refused with HTTP 413 beyond 100,000 entries; an unknown or repeated `utxo` value is HTTP 400.
  The devnet comparator uses `utxo=full`.
