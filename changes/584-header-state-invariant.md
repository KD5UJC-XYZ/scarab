### Fixed (configuration, #584)
- **The hard-fork triggers of a test network are read as the reference reads them.** `Test<Era>HardForkAtEpoch` and
  `Test<Era>HardForkAtVersion` are the reference's `Maybe EpochNo` and `Maybe Word`: a whole number from 0 to 2^64 - 1, in
  any notation, or null for absent. A string, a negative or fractional number, or one beyond `Word64` is refused at
  startup with a typed configuration error (`ConfigProblem.ConfigNumber`) instead of being accepted as any integer.
- **A header's ledger state out of range is documented as an invariant.** The only values that could fail there, in the
  Byron to Shelley hand-over of the header forecast, are a first Shelley epoch and the genesis delegation keys. The
  configuration and genesis readers refuse out-of-range values at startup, and the hand-over is entered only for a header
  whose epoch is at least the first Shelley epoch. So a peer cannot reach that failure.
