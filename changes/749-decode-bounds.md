### Fixed (decoding bounds, #749)

- Headers, blocks and transactions are decoded with no budget of CBOR items, string bytes or nesting, as the reference's decoders read them; the mini-protocol codecs and the multiplexer bound their bytes and the rules (`maxBHSize`, `maxBBSize`, `maxTxSize`) their sizes after decoding. A Leios header whose issuer key is written as hundreds of empty chunks within `maxBHSize`, and a Byron epoch boundary header with hundreds of attributes, are now read as the reference reads them (`fixtures/reference/header-decoding`).
- LocalTxSubmission reads a transaction of any size, as the reference does, and the ledger judges it by `maxTxSize` instead of the connection ending at 1 MiB; a LocalStateQuery query over 2,000,000 bytes or 200,000 items is answered.
- The live local-submission handler uses the same decoding bounds as the mempool. LocalTxSubmission
  retains framing progress across fragmented messages instead of rescanning completed CBOR elements
  and string chunks, and its remaining item and nesting caps are removed. LocalStateQuery retains
  its separately approved depth deviation (#474).
