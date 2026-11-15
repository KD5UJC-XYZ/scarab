### Added (Antithesis assertions, #1032)

- **The node asserts what its components meet, from their typed events** (with `--assertions-file`). `sometimes`:
  Scarab switches to a fork, rolls back more than 1 block, and more than k / 2 blocks (the follower's commit, k
  the chain's); the governor demotes a peer it chose to, a peer is demoted asynchronously, and a peer's chain
  no longer intersects ours (the asynchronous demotion of a hot peer whose ChainSync returned
  `NoMoreIntersection`); the mempool drops a transaction on revalidation; Scarab forges a block, and one with
  transactions; a ledger snapshot is written. `unreachable`: Scarab forges a block its own rules refuse, builds
  a block that fails a check only its own bug can fail (a forge defect), a block's validation ends without a
  verdict, the node stops on a fault other than a clock moved back further than the forger's rewind limit (the
  start's fault included), and the slot loop traces the clock moved back for a reading that is later than the
  previous one and more than 1 µs but less than 1 ms before the slot start it waited for.

### Changed (log fields, #1032)

- `peerSelectionDemote` of an `asynchronous` demotion has `returned`: the type of the hot peer's ChainSync
  return (`Returned`, `NoMoreIntersection`, `RolledBackPastIntersection`, `AskedToTerminate`) or null; its
  `reason` text is unchanged. The return reason is a closed type of the governor's vocabulary now.
- `mempoolDropped` has `cause`: `Revalidation` (the pool, validated again at a new tip or after a removal, no
  longer admits the transaction) or `Withdrawal` (the forger had no verdict on it while it built a block).
