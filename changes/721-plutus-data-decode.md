### Fixed (Plutus Data read at decode, #721)

- A witness set's datums, a redeemer's data and an output's inline datum are read as Plutus `Data`
  when a transaction or a block is decoded, as the reference reads them: one that is not `Data`
  fails the decode. Such a block was accepted before, where every reference node refuses it.
