- Shelley, Allegra and Mary auxiliary data is now checked structurally before the hash/presence
  predicate, as the released ledger does: the 64-byte metadatum bound from protocol 3, the
  `[metadata, native scripts]` list only from Allegra, no tag 259 or Plutus fields, and typed
  decoding refusals that precede hash mismatches. Original bytes and hashes are unchanged. Backed by
  504 unchanged-Haskell observations at protocols 2-4.
