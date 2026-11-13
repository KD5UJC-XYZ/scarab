### Fixed (ledger UTXOS rule, #881)
- **UTXOS evaluates the scripts and takes the collateral in every era from Alonzo, whatever the protocol
  version.** Below version 5 Scarab skipped the evaluation, so it accepted a transaction whose `is_valid` flag
  the scripts contradict, and took no collateral from one that fails phase 2 (`Alonzo/Rules/Utxos.hs:159-238`,
  `Alonzo/Rules/Utxo.hs:775-784`, `Babbage/Rules/Utxo.hs:474-485`). Only a chain that starts Alonzo by `TestAlonzoHardForkAtEpoch` below
  version 5 is affected; mainnet, preprod, preview and the Leios testnet are not.
