### Fixed (ledger rules)

- A Shelley to Babbage transaction with a MIR certificate counts its bootstrap witnesses towards
  the genesis delegates' quorum by their address roots, as the reference's
  `keyHashWitnessesTxWits` does, where only its vkey witnesses counted.
- A Byron transaction's outputs past the first 65,536 enter neither the UTxO nor its output
  balance, as the reference's `txOutputUTxO` indexes them with a `Word16`; every output's network
  magic and attributes are still checked.
- A Byron input's witness key is checked against the address root over the address's attributes
  encoded again with minimal heads and lengths, as the reference's `checkAddrSpendingData` does,
  where the attributes were hashed as written. Shelley-era bootstrap witnesses still hash their
  own attribute bytes as written.
- A Conway ledger state read from a reference node's `state` file, or imported from a canonical
  ledger state (SCLS), has its proposals rebuilt as the reference's `mkProposals` rebuilds them: a
  proposal whose previous action is neither its lineage's root nor an earlier proposal of its
  lineage is refused, and of two proposals with one id in a state file the first is kept. A native
  snapshot's proposals are read as the snapshot wrote them, so no existing snapshot is refused or
  deleted for this rule.
