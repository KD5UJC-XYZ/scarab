### Added (node-to-client queries before Conway, #924)

- LocalStateQuery answers, in every Shelley-based era and not only in Conway, the block queries that read
  nothing of the era's own, each with its `GetCBOR` form, at every node-to-client version, as the reference
  does: `GetLedgerTip` (tag 0), `GetEpochNo` (1), `GetProposedPParamsUpdates` (4, the empty map whatever the
  state, the proposals of the eras before Conway too), `GetGenesisConfig` (11, the legacy record below
  version 21), `GetAccountState` (29, the treasury and the reserves) and `GetMaxMajorProtocolVersion` (38).
  In an era before Conway they used to close the connection.
