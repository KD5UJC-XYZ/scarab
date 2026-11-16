### Added (node-to-client queries before Conway, #924)

- LocalStateQuery answers `GetCurrentPParams` (tag 3) and `GetGovState` (24), each with its `GetCBOR` form,
  in every Shelley-based era from Shelley to Babbage and not only in Conway, as the reference does: the
  current protocol parameters in the era's encoding (below node-to-client version 21 in the legacy encoding
  the reference uses before Conway, the protocol version as two fields), and the era's governance state
  (`ShelleyGovState`: the proposed updates and the current, previous and future parameters) at every
  version. In an era before Conway they used to close the connection. A Dijkstra state answers neither yet.
