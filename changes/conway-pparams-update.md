### Fixed (Conway governance)

- Decode the parameter update of a Conway parameter-change proposal as the reference's Conway
  `PParamsUpdate`: exactly the Conway update keys, each value at its field's type, and a repeated
  key refused, when the transaction is decoded. Enacting a ratified parameter change applies the
  typed update and cannot fail. Ledger-state snapshots keep the same encoding.
