### Changed (consensus geometry, #399)

- Compute the stability window (3k/f) and the randomness stabilisation window (4k/f) with exact rational arithmetic rounded up, as cardano-ledger's `computeStabilityWindow` does; the values for the Mainnet, Preprod and Preview configurations are unchanged.
