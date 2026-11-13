### Fixed (ledger BBODY rule, #882)
- **BBODY limits the execution units of the block in Alonzo and Babbage, and the reference scripts of the
  block in Conway, whatever the protocol version.** Scarab skipped the first below version 5 and the second
  below version 9, so Alonzo at version 4 and Conway at version 7 or 8 accepted blocks over the limits
  (`Alonzo/Rules/Bbody.hs:149-167,212`, `Conway/Rules/Bbody.hs:314,330-355`); Shelley to Mary no longer check
  execution units at any version, as their BBODY does not. Only a chain that starts Alonzo or Conway by epoch
  below its usual version is affected; mainnet, preprod, preview and the Leios testnet are not.
