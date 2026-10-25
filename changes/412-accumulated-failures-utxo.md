### Changed (UTXO, #412)

- UTXO records every failing predicate, in the order of each era's reference rule (Shelley, Allegra
  and Mary with PPUP between the value and the output checks; Alonzo; Babbage and Conway's
  `babbageUtxoValidation`), instead of stopping at the first: the fee and collateral (`feesOK`), every
  input that does not exist, the value, the outputs, the networks, the sizes and the execution units
  are all reported. The collateral checks follow the reference's order and report its values (a
  collateral return taken off the balance, `NoCollateralInputs` when no collateral input exists).
  Alonzo no longer counts the collateral inputs, which its reference rule does not do; Babbage
  and Conway do. An output too big is reported last output first from Alonzo, as the reference
  collects them.
