### Changed (HTTP query server, #1150)

- `GET /state` builds one answer at a time. A request for the point and `utxo` view of an answer being built
  waits for that build and gets its answer, and the answers at the tip are kept, rendered, until the node publishes
  its next committed tip: asking again at the same tip builds nothing. Concurrent requests no longer each build the
  whole projection of the ledger state. The summary is not limited in size, and the server stays on by default, on
  loopback (`--query-listen off` turns it off).
