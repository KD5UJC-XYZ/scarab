### Fixed (ledger, #1448)

- A snapshot of a Shelley-era state keeps the Byron redeem (AVVM) outputs that the Byron to Shelley
  hand-over stashes, written into the state file as the reference writes them. Before this fix the
  stash was written as an empty map. A node restored from such a snapshot then returned nothing to
  the reserves on entering Allegra. On mainnet its reserves were 318,200,635 ada short from epoch 236,
  so every reward from epoch 235 on was short, and a valid withdrawal at slot 17,020,941 was rejected.
- A Shelley-era snapshot written by an earlier build still holds no stash. Replay through Shelley from
  a snapshot taken before the hand-over.
