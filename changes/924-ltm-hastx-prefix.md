### Fixed (node-to-client, #924)

- LocalTxMonitor `HasTx` ends the connection as soon as the part of a transaction id that has arrived is one
  the reference's decoder fails on, as the reference's codec does, instead of waiting for the rest of the id:
  `82 07 82 08` (an era past the last), an envelope of three items or Byron's fifth constructor now ends it at
  once. A prefix the decoder may still read waits as before, and a whole id gets the same answer or refusal as
  before.
