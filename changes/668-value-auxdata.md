### Fixed (an output's value and the list form of auxiliary data are definite lists, #668)

- The `[coin, multiasset]` value of an output and of a collateral return (Mary to Conway) and the
  `[metadata, native scripts]` list of auxiliary data (Alonzo to Conway) are definite lists only in
  a block, as the reference's `RecD` decoders read them below decoder version 12. An indefinite
  list refuses the body or transaction with a typed `IndefiniteLength`; it was accepted before.
- An output an earlier build stored with its value as an indefinite list still loads from the UTxO
  store and the snapshot (ADR 0006): a stored output reads its value in either form.
