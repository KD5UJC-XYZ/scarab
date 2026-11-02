### Changed (devnet e2e ledger comparison, #433)

- `devnet.py e2e` now also compares each era's complete ledger state at the same point on both
  nodes: the `DebugNewEpochState` answer over node-to-client, every byte. A difference, an error or
  too few matches fails the run, as for the projected comparison. That answer holds the UTxO as an
  empty map on both nodes, so the UTxO is still compared only by the projection (#677). The `e2e`
  follower opens a node-to-client socket for this (`devnet.py follow --local-socket`).
