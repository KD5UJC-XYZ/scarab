### Fixed (ledger, #492)
- Represent native Coin as a signed integer while retaining bounded Coin decoding and compact conversion.
  Mirror wrapped compact account, reward and deposit arithmetic at the reference boundaries,
  including signed treasury/reserve results. Preserve stored bytes and withdrawal map ordering,
  with Coin amounts at the decoded/native API boundary.
