### Fixed (ledger Conway LEDGER rule, #885)
- **Conway's LEDGER requires a DRep delegation of a key account it withdraws from at every protocol version but
  9.** Scarab skipped the check below version 10; the reference skips it only in the bootstrap phase
  (`hardforkConwayBootstrapPhase`, `Conway/Era.hs:257-258`; `Conway/Rules/Ledger.hs:379-380`), so Conway at
  version 7 or 8 accepted a withdrawal the reference refuses. Only a chain that starts Conway by
  `TestConwayHardForkAtEpoch` below version 9 is affected; mainnet, preprod, preview and the Leios testnet are
  not.
