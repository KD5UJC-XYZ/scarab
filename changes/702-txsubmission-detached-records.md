### Fixed (TxSubmission2 transactions with detached RecD records, #702)

- The node-to-node transaction submission reads a Conway transaction holding a `RecD` record
  written as an empty indefinite list as the reference's ledger decoder does: a peer's reply with
  such a transaction is no longer a protocol error, its id is the hash of the body span the
  reference reads, so it matches the id the peer offered, and the node can send such a transaction
  it holds. The protocol codec takes the era's transaction reader as the reference's codec takes
  its decoder; the node supplies the ledger's.
