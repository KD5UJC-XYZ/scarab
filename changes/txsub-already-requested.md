### Added (TxSubmission2 served bytes)

- `scarab_txsubmission_served_bytes_total` counts the bytes of the transactions the node sends to peers in
  TxSubmission2 replies, and `GET /connections` gives the same figure per hot session and per peer
  (`sessions[].active.txBytesServed`, `sessions[].lifetime.txBytesServed`).
