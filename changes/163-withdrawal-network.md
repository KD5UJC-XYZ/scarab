### Fixed (Conway transaction validation against the reference, #163)
- **The network of a withdrawal account is checked for every Conway transaction, whatever its validity tag.** The rule UTXO
  now makes the reference's `validateWrongNetworkWithdrawal` (`Babbage/Rules/Utxo.hs:400`, run by `babbageUtxoValidation`
  for Conway too) and refuses with `WrongNetworkWithdrawal`, the failure the node-to-client interface already encoded
  (tag 8). Before, a transaction the reference refuses could be accepted by the node, in a block as well as for the mempool.
- **Three more predicates of `babbageUtxoValidation` that the node made only in part.** The collateral inputs must exist
  and be at most `maxCollateralInputs` whether or not the transaction has redeemers (`allInputs`, `Babbage/TxBody.hs:228`;
  `validateTooManyCollateralInputs`, `Babbage/Rules/Utxo.hs:412`). The outputs that hold a bootstrap address are checked
  for their attributes (`OutputBootAddrAttrsTooBig`, at most 64 bytes, `validateOutputBootAddrAttrsTooBig`,
  `Babbage/Rules/Utxo.hs:392`) and for their network, taken from the address as the reference does (`getNetwork`,
  `Cardano/Ledger/Address.hs:175`: no network magic is the main network).
- Tests: `ConwayLedgerSuite` (each validity tag, as a transaction and as a block, at protocol versions 10 and 11; the
  collateral only taken where the rules of UTXO hold; the collateral and bootstrap-address predicates), the `ntc` scenario
  (refused transactions equal to the reference's as raw bytes, and a transaction that takes its collateral on both nodes).
