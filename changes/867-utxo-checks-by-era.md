### Fixed (ledger UTXO rules, #867)
- **Each era's UTXO runs its own checks whatever the protocol version.** Scarab chose them by the protocol
  version, which splits from the reference on a chain that starts an era by epoch (`Test<Era>HardForkAtEpoch`)
  below that era's usual version. Shelley keeps its inclusive time to live and Allegra and Mary their validity
  interval with an exclusive upper bound; Allegra and Mary refuse a value over 4000 bytes; each era takes the
  minimum ada of its own `getMinCoinTxOut` (Mary never finds an output with assets too small, Alonzo counts
  `coinsPerUTxOWord`, Babbage on `coinsPerUTxOByte`); Babbage reports its own `BabbageOutputTooSmallUTxO` and
  takes the collateral return off the collateral; Alonzo on lists the outputs too big last first. Mainnet,
  preprod, preview and the Leios testnet run each era at its usual version and are not affected.
- The UTXOW, UTXOS, BBODY, script-context and protocol-parameter sites of the same kind are tracked by #880,
  #881, #882, #883 and #884; a wrong bootstrap-phase predicate in Conway's LEDGER by #885.
