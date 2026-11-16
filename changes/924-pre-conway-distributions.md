### Added (node-to-client queries before Conway, #924)

- LocalStateQuery answers `GetNonMyopicMemberRewards` (tag 2), `GetStakeDistribution` (5),
  `GetRewardInfoPools` (18), `GetPoolDistr` (21), `GetLedgerPeerSnapshot` (34, both forms), `GetPoolDistr2`
  (36) and `GetStakeDistribution2` (37), each with its `GetCBOR` form, in every Shelley-based era from Shelley
  to Babbage and not only in Conway, as the reference does: the pool distribution in force, the stake
  distribution of the current stake (with the stake of pointer addresses before Conway), the pools' reward
  information, the non-myopic member rewards and the ledger peer snapshots, the distributions written at the
  era's lowest version, so their ratios are untagged before Conway. In an era before Conway they used to close
  the connection. A Dijkstra state answers none of them yet.

### Fixed (node-to-client queries, #933)

- `GetPoolDistr` and `GetPoolDistr2` with `Just` an empty set of pools answer every pool, as the reference
  does, and as they do for `Nothing`; they answered no pool, in Conway too. Both now read the pool
  distribution in force as the state holds it.
