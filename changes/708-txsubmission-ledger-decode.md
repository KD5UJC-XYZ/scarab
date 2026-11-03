### Fixed (TxSubmission2 reads a reply's transaction whole, #708)

- The node-to-node transaction submission reads each transaction of a peer's reply with the
  ledger's decoders, as the reference's codec does: a transaction they refuse is now a codec
  failure that ends the session, where it used to pass the codec and be refused by the mempool
  with the session going on. A decoder that throws remains a defect of the connection's input,
  not the peer's fault. Transactions the node sends are checked as before, by their envelope.
