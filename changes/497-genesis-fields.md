### Changed (ledger, #497)

- **The Shelley, Alonzo and Conway genesis files are read as strictly as the reference reads them.** At start-up the node checks each file field by field against the reference's `FromJSON` instances (`GenesisSchema`), before any reader of its own runs. A field the reference requires is required, of the type it reads it with (the `Word16`, `Word32` and `Word64` bounds, the bounded ratios as decimals or objects, the nonce tag, the network). A refusal is a typed `ConfigProblem.GenesisField` naming the file and the field. These used to be filled in with defaults when absent:
  - the Shelley `maxBlockHeaderSize`, `decentralisationParam`, `extraEntropy`, `updateQuorum` and `genDelegs`;
  - the Alonzo execution prices and units, `collateralPercentage` and `maxCollateralInputs`;
  - the Conway thresholds, committee, constitution and governance parameters.
  `minPoolCost` is optional, as in the reference.
- A refused Conway genesis no longer becomes "no Conway parameters".
