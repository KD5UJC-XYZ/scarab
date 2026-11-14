### Added (TxSubmission2 served bytes)

- `scarab_txsubmission_served_bytes_total` counts the bytes of the transactions the node sends to peers in
  TxSubmission2 replies, and `GET /connections` gives the same figure per hot session and per peer
  (`sessions[].active.txBytesServed`, `sessions[].lifetime.txBytesServed`).

### Changed (TxSubmission2 reply bound)

- A TxSubmission2 reply carries the requested transactions only while the reply stays within the receiver's
  2,500,000-byte limit for state `StTxs`; the rest are omitted, as a transaction no longer held is. The bound
  is not reached with the current limits (at most eight transactions of at most 65,536 bytes a reply).

### Changed (TxSubmission2 body requests)

- The node ends a TxSubmission2 connection whose peer asks for a transaction id none of whose
  advertisements in the unacknowledged window is left unasked (`AlreadyRequested`), after the network
  specification's rule that ids already asked for may not be asked for again. An id offered more than once
  in the window may be asked for once per advertisement, so no request of an honest peer is refused. Stricter
  than the reference's serving side; an approved deviation (ADR 0008, amendment of 2026-11-14).
