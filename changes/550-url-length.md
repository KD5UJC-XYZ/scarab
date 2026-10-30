### Fixed (url length at body decode, #550)

- A pool metadata url or a governance anchor url longer than 64 bytes of UTF-8 below decoder
  version 9 (Shelley to Babbage), or 128 bytes from it (Conway), is refused when the transaction body
  is decoded, with `TextTooLong` naming the field, as the reference's `textDecCBOR` refuses it. Such a
  url was accepted before at any length.
