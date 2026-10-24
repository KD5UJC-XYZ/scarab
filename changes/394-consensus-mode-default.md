### Added (default ConsensusMode recorded, #394)
- **ADR 0022 "Defaults follow the reference binary"** records that `ConsensusMode` defaults to `PraosMode` as cardano-node 11.1.3 does, and that a `--config` value is honoured as written.
- **A `praosSyncUntrusted` start-up warning** (`Node.PraosSyncUntrusted`) when the node starts in Praos mode with an empty or more-than-an-epoch-behind chain and no bootstrap peers or trustable local roots. See "Choosing the consensus mode" in `docs/running.md`.
