### Fixed (bootstrap witness chain codes)
- **A bootstrap witness's chain code is read at any length, as the reference reads it below decoder version 12.**
  `instance DecCBOR ChainCode` (cardano-ledger `Keys/Bootstrap.hs:72-78`) checks for 32 bytes only from version 12,
  and Shelley to Conway are read at 2 to 9; the witness set decoder required 32 bytes in every era. The chain code is
  kept as written and hashed as written into the witness's address root, as `bootstrapWitKeyHash` does
  (`Bootstrap.hs:112-146`). `fixtures/reference/bootstrap-chain-codes` holds 147 cases recorded from the unchanged
  released decoders, with the address roots they compute.
