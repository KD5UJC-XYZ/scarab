### Added (Ouroboros Genesis, #33)

- Integrate the pinned reference's Genesis state machine, limit on eagerness,
  density disconnector, limit on patience, ChainSync jumping and devoted
  BlockFetch coordination across outbound and inbound duplex sessions.
- Retain received original block bodies independently of the adopted chain so
  postponed blocks and forks remain available after restart. The additional
  journal reuses the existing volatile record encoding; native V5 snapshots
  retain their format.
- Enforce configured lightweight checkpoints during header and block validation,
  including Byron, and verify checkpoint file hashes before parsing.
- Add original native decision corpora, simulator schedules and concrete TCP
  ordering observations, plus explicit Genesis-mode devnet gates.

### Fixed (connection lifecycle, #33)

- Drain normal ChainSync returns before releasing hot protocols, preserve warm
  KeepAlive sessions, and apply the reference repromotion delays before a fresh
  activation. Preserve typed cached-block attribution through inbound validation
  failures instead of blaming the current requesting peer for recovered bytes.
