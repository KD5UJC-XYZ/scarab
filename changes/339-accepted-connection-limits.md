### Changed (accepted connections and BlockFetch server, #339, #474)
- **Accepted connections follow the reference's limits**: hard 512, soft 384 and a 5 s delay (`AcceptedConnectionsLimit`,
  ouroboros-network `2fb69829`, `Server/RateLimiting.hs`, `Diffusion/Configuration.hs`). The accept loop delays the next accept in
  proportion to the excess over 384 connections and, at the hard limit, waits for a drop and at least 5 s. The configuration's `AcceptedConnectionsLimit`
  (`hardLimit`, `softLimit`, `delay` in seconds, as cardano-node) sets them and `--max-inbound` overrides the hard limit (default 512, was 64).
  The limits take any `Word32` and the delay any `DiffTime` aeson accepts (any sign, floored to a picosecond, exponent at most 1024), and
  the soft delay is computed exactly, as `DiffTime` arithmetic, so a long delay cannot overflow into a negative sleep.
  The reference's three accept-policy traces are logged as `connection` lines (`acceptRateLimited`, `acceptHardLimit`, `acceptResumed`).
- **The per-address inbound limit defaults to 64** (was 8): a deviation approved by the owner on 2026-10-25, still configurable.
- **The BlockFetch server serves a range of any length** instead of refusing more than 2,160 blocks, as the reference server does
  (`MiniProtocol/BlockFetch/Server.hs`, ouroboros-consensus `82ecba32`). A range that starts in the immutable store and ends in the
  volatile chain reads the store only up to the boundary it captured, so blocks that become immutable while it streams are not sent twice.
