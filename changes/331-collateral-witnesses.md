### Fixed (ledger, #331)
- **The key witnesses of collateral inputs are required whether or not the transaction has redeemers.** UTXOW took the
  collateral inputs into the needed key witnesses only for protocol 5 or later and only when the transaction carried
  redeemers, so a transaction whose collateral input belongs to a key that did not sign was accepted when it had no
  redeemers. The reference needs the payment key of every spendable input, which from Alonzo on is the inputs and the
  collateral inputs (`getShelleyWitsVKeyNeededNoGov` over `spendableInputsTxBodyF`), and an earlier era has no collateral
  field. The transaction is now refused with `MissingVKeyWitnesses`. `CollateralWitnessSuite` covers protocols 5 to 11.
