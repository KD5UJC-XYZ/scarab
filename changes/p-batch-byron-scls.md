### Fixed (Conway governance, #970)

- Adding a proposal whose id is already stored replaces that entry where it stands, as the
  reference's `OMap.||>` does, instead of storing it a second time. Valid transactions cannot reach
  this: a proposal's id is its transaction's id and an index.

### Fixed (Byron ledger, #969)

- Check a Byron transaction in the order of the reference's `updateUTxOTxWitness`: the minimum fee
  before the output balance, the output balance before the input balance, and the transaction and
  output attributes and output network magic after the fee. A transaction with more than one fault
  is now refused with the error the reference reports first; every verdict is unchanged. An output
  address that does not decode is refused before the transaction's other checks.
