### Fixed (node-to-client query availability, #924)

- LocalStateQuery no longer refuses a Shelley block query for the negotiated node-to-client version (16 to
  23), as cardano-node 11.1.3 refuses none: the reference's server decodes a block query alike at every
  version, and its supported-version table binds only the encoder of reference clients. The Conway query
  forms outside that table used to close the connection, among them `GetDRepDelegations` and the ledger-peer
  snapshot by kind below 23, `GetStakeDistribution2` below 21, `GetProposedPParamsUpdates` from 20 and
  `GetStakeDistribution` and `GetPoolDistr` from 21. A query of an era the ledger is not in is answered with
  the era mismatch at every version.
- The version still selects the encodings it selected: `GetGenesisConfig` in the legacy record below 21,
  the old ledger-peer snapshot without SRV relays below 22, and `GetCBOR` at the latest encoding at every
  version.
- The `ntc` scenario's version matrix compares its forms (Conway's version-limited queries, `GetCBOR` forms
  of them and queries of another era) with the reference node byte for byte at every version, where it used
  to require a refusal outside the table. `DebugLedgerConfig` and the queries not implemented before
  Conway remain unanswered.
