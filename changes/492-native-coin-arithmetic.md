### Fixed (ledger, #492)
- Represent native Coin as a signed integer while retaining bounded Coin decoding and compact conversion.
  Mirror wrapped compact account, reward, deposit and stake arithmetic at the reference boundaries,
  including wrapped-zero stake entries and signed treasury/reserve results. Preserve stored bytes and
  withdrawal map ordering; compare arithmetic and Coin encoding with eleven captured native API cases.
