### Fixed (output addresses of header kind 8)
- **An output address of header kind 8 is decoded in full, as the reference decodes a bootstrap address.** The reference reads the
  whole byte string as the CRC-protected Byron `Address` in every era (`decodeBootstrapAddress`) and fails the decoder, and with
  it the transaction, when the envelope, the checksum or the payload does not read. `Address.decode` now does the same and
  returns the typed cause of the Byron decoder; it no longer keeps such an address with an empty root. The reference corpus of
  `fixtures/reference/conway-decoding` gains ten cases (five per era) recorded from the unchanged released decoders.
- **The Byron `Address` reader enforces the rules of the reference's version-1 decoder.** The header byte is exactly 0x82; indefinite-length
  bytes, lists and maps are refused; the attribute map has `Word8` keys in strictly ascending order; the address type is a canonical
  `Word8` of 0 or 2; the derivation path is canonical bytes and the network magic a canonical `Word32`; the root is 28 bytes. The
  non-minimal integer and length heads the reference keeps inside the envelope are still kept. The corpus gains sixty-six cases
  (thirty-three per era), recorded from the unchanged released decoders.
