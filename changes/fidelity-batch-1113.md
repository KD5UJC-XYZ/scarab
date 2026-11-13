### Fixed (ledger rules)

- A Shelley to Babbage transaction with a MIR certificate counts its bootstrap witnesses towards
  the genesis delegates' quorum by their address roots, as the reference's
  `keyHashWitnessesTxWits` does, where only its vkey witnesses counted.
