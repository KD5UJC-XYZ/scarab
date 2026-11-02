### Changed (devnet ledger comparisons, #433)
- **`devnet.py pool` and `devnet.py soak` compare the complete ledger state.** At each ledger comparison both
  scenarios now also ask `DebugNewEpochState` and `GetUTxOWhole` of both nodes at one point, compare every byte
  (`compare.whole_state`) and fail the run on a difference, an error or too few matches, as `e2e` does. `soak`
  takes the point at the follower's tip; `pool`, whose Scala node forges, at the reference's tip, asking the Scala
  node again while it has not reached it. Their summaries carry `wholeState`. The pool's Scala node always opens
  its node-to-client socket now.
- The register entry `devnet-ledger-projection` (bounded) is replaced by `devnet-ledger-whole-state` (covered).
