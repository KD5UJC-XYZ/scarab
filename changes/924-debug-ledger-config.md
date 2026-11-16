### Added (node-to-client, #924)

- LocalStateQuery answers the top-level `DebugLedgerConfig` from node-to-client version 20, in every era,
  with the ledger configuration of the hard-fork combinator as the reference writes it: the parameters
  of the eight eras, Byron's genesis configuration, and each Shelley-based era's compact genesis,
  `Globals` fields, translation context (the Alonzo, Conway and Dijkstra genesis among them) and
  hard-fork trigger. Below version 20 the query is refused at decode, as the reference refuses it; it
  used to close the connection at every version.
- `GetGenesisConfig` reads the Shelley genesis as the reference does: a ratio of the protocol parameters
  given as a rational object, and an absent minimum output value or minimum pool cost (zero), no longer
  close the connection; more than 23 genesis delegates are written as an indefinite map, as the
  reference writes them.
