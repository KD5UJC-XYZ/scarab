### Changed (accepted connections and BlockFetch server, #339, #474)
- **Accepted connections follow the reference's limits**: hard 512, soft 384 and a 5 s delay (`AcceptedConnectionsLimit`,
  ouroboros-network `2fb69829`, `Server/RateLimiting.hs`, `Diffusion/Configuration.hs`). The accept loop delays the next accept in
  proportion to the excess over 384 connections and, at the hard limit, waits for a drop and at least 5 s. `--max-inbound` sets the hard
  limit (default 512, was 64).
- **The per-address inbound limit defaults to 64** (was 8): a deviation approved by the owner on 2026-10-25, still configurable.
- **The BlockFetch server serves a range of any length** instead of refusing more than 2,160 blocks, as the reference server does
  (`MiniProtocol/BlockFetch/Server.hs`, ouroboros-consensus `82ecba32`).
