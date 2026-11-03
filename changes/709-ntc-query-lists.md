### Fixed (node-to-client query lists and sets, #709)

- A node-to-client query is read with the decoders the reference uses for each of its parts: its
  envelopes, a plain `Maybe` and an `Either` are definite lists only; a set read with cardano-binary's
  `fromCBOR` must carry tag 258 and list its elements in strictly ascending order; a set read with
  the ledger's decoder may not hold an element twice; and the addresses of `GetUTxOByAddress` are
  read as addresses. A query the reference refuses on these grounds is refused, where it used to
  be answered.
