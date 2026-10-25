### Fixed (Babbage and Conway minimum output value)
- **The minimum ada of an output is computed over the length of the output as it was decoded.** The reference sizes an output by
  the span the decoder consumed (`Sized`, `decodeSized`, `validateOutputTooSmallUTxO`), whatever its form or address encoding.
  `TxOut.ledgerSize` is now that length: the two bytes taken off a map-form output and the re-encoded pointer address no longer
  change it. Pre-Babbage minimums are unchanged.
