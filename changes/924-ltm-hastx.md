### Fixed (node-to-client, #924)

- LocalTxMonitor `HasTx` reads a transaction id of any era with that era's decoder and finds a held
  transaction by its hash whatever the era, as the reference does: a held Conway transaction is found under
  Babbage's tag, or any era's, and under each of Byron's four kinds of id. An id the era's decoder refuses (a
  hash of other than 32 bytes, a Byron id without its constructor, an era past the last) now ends the
  connection as the reference's codec does, instead of being answered false; before, only an id tagged with
  Conway's era was looked up.
