### Fixed (ledger decoding, #329)

- Below decoder version 9 (Shelley to Babbage), collapse a repeated set element by its decoded value, as the reference does, so two encodings of one input or witness are one element. The original bytes that are hashed are unchanged.
