### Fixed (negotiated node-to-client query versions, #168)
- LocalStateQuery acquisitions retain the agreed version (16–23). Query support follows the pinned
  reference's supported-version table, including constructors nested in `GetCBOR`.
- Before version 21, `GetGenesisConfig` writes the legacy Shelley protocol-parameter record and
  `GetPoolDistr`/`GetStakeDistribution` return the legacy pool distribution. Conway's
  `GetCurrentPParams` bytes are identical on both sides of that boundary; `GetCBOR` deliberately
  uses the latest internal answer encoding, as the reference does.
- The `ntc` scenario explicitly negotiates every supported version, compares supported answers
  byte for byte and separately verifies refusals outside the supported query contract. Scarab
  enforces that contract on its server boundary; the reference enforces it on its client encoder.
