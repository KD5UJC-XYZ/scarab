### Fixed (node-to-client, #924)

- LocalTxSubmission ends the connection as soon as the part of a transaction that has arrived is one the
  reference's decoder fails on in the transaction's envelopes, as the reference's codec does, instead of
  waiting for the rest of the message: `82 00 82 08` (an era past the last), an envelope of three items or an
  indefinite one, Byron's fifth constructor, a transaction of a Shelley-based era not under tag 24, a tag
  other than 24 or a chunked string under it now ends it at once. The envelopes are read as far as tag 24 and
  the head of its byte string (Byron's payload as far as its constructor); a deeper token is judged once the
  message has all arrived.
- LocalTxSubmission reads a whole transaction as the reference's decoder does: a definite `[era, tx]` and, in
  a Shelley-based era, tag 24 around a definite byte string, each head at any width. An indefinite envelope, a
  transaction not wrapped in tag 24 and a chunked string under it, which the node used to admit, are refused
  as malformed.
- LocalStateQuery ends the connection as soon as the part of a query that has arrived is one the reference's
  decoder of queries fails on in its envelopes, instead of waiting for the rest: a top-level, block or ledger
  query's size and tag it has no case for (`82 03 82 0f`), an era index past the last
  (`82 03 82 00 82 00 82 08`) or a query only Conway reads in an earlier era now ends it at once. The
  envelopes are read as far as the ledger query's size and tag and the peer kind; a query's arguments are
  judged once it has all arrived, and a whole query is read and answered as before.
