### Fixed (node-to-client, #924)

- LocalTxSubmission ends the connection as soon as the part of a transaction that has arrived is one the
  reference's decoder fails on, as the reference's codec does, instead of waiting for the rest of the message:
  `82 00 82 08` (an era past the last), an envelope of three items, Byron's fifth constructor or a tag other
  than 24 now ends it at once. A prefix of a message the node reads whole waits as before (among them an
  indefinite envelope, a transaction not wrapped in tag 24 and a chunked string under it), and a whole message
  gets the same answer as before.
- LocalStateQuery ends the connection as soon as the part of a query that has arrived is one the reference's
  decoder of queries fails on, instead of waiting for the rest: a top-level, block or ledger query's size and tag
  it has no case for (`82 03 82 0f`), an era index past the last (`82 03 82 00 82 00 82 08`) or a query only
  Conway reads in an earlier era now ends it at once. A whole query is read and answered as before.
