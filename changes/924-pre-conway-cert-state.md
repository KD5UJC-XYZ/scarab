### Added (node-to-client queries before Conway, #924)

- LocalStateQuery answers the queries of the certificate state in every era from Shelley to Babbage and not
  only in Conway, each with its `GetCBOR` form, as the reference does: `GetFilteredDelegationsAndRewardAccounts`
  (tag 10), `GetStakePools` (16), `GetStakePoolParams` (17), `GetPoolState` (19), `GetStakeSnapshots` (20) and
  `GetStakeDelegDeposits` (22), read from the era's accounts and pools and written in the era's encoding (a
  pool's owners a plain list before Conway, `GetStakePools` a tagged set in every era). In an era before
  Conway they used to close the connection. A Dijkstra state answers none of them yet.

### Fixed (node-to-client queries, #924)

- `GetStakeSnapshots` without a list of pools, before protocol version 11, lists every pool with a delegator in
  a snapshot, with zeros where its delegators hold no stake, and gives each snapshot's total active stake, as
  the reference's `queryStakeSnapshots` does; it used to leave out a pool whose delegators hold no stake.
