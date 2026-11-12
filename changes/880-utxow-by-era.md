### Fixed (ledger, #880)

- UTXOW runs the era's witness checks whatever the protocol version, including supplemental datums,
  malformed scripts and auxiliary-data decoding. An absent redeemers field is hashed using the
  era's empty encoding. This fixes epoch-triggered private chains whose era and protocol version
  differ; script decoding and the version-11 integrity-mismatch reason still use the protocol version.
