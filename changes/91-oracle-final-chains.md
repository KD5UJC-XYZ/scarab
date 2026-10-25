### Fixed (devnet ledger oracle, #91)

- `devnet.py e2e --oracle`, `soak --oracle` and `oracle` compare an epoch-boundary digest only when its block, identified by header hash, is on both final chains. These are reference node 1's ImmutableDB and the follower's chain, rebuilt from its log. A digest of a block that a one-block fork rolled back is no longer compared with the state of another block.
- Every other digest is excluded with a reason: `rolledBack`, `notFinalOnFollower`, `notYetImmutable` or `rolledBackOnFollower`. The summary counts compared, matched and excluded digests and lists the epochs left without a matched digest.
- A run fails on any difference or error, on a block one side holds `k` deep that the other side's final chain does not have, and when no digest is compared and matched.
- Each digest file is now its own row. Two digests at one slot used to overwrite each other.
