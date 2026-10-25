### Fixed (native script evaluation)
- **Native scripts are evaluated over the key hashes of the vkey witnesses only.** The reference evaluates timelock and
  multi-signature scripts over `addrTxWitsL` (`validateTimelock`, `validateMultiSig`, `validateFailedNativeScripts`); the
  address root of a bootstrap witness still counts for the needed key hashes (`MissingVKeyWitnesses`) but is no longer a signer
  of a script.
