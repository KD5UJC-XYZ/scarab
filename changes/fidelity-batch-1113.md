### Fixed (ledger rules)

- A Shelley to Babbage transaction with a MIR certificate counts its bootstrap witnesses towards
  the genesis delegates' quorum by their address roots, as the reference's
  `keyHashWitnessesTxWits` does, where only its vkey witnesses counted.
- A Byron transaction's outputs past the first 65,536 enter neither the UTxO nor its output
  balance, as the reference's `txOutputUTxO` indexes them with a `Word16`; every output's network
  magic and attributes are still checked.
