### Fixed (state codecs, #996)

- Conway parameter updates now write definite maps of decoded field values in reference order.
  Pool relays and metadata are re-encoded from their values in state exports and query answers,
  sharing the transaction encoders without their validity checks. Governance state writes its
  held values without transaction validation, preserving node-to-client debug answers.
