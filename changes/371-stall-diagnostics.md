### Added (#371: the follower stall leaves evidence)
- **A `stallState` event after `stalled`.** When `StallWatchdog` reports a stall, the node logs every session's
  pipeline once: the `FetchCoordinator`'s pending offers, active reservations and candidate headers, the
  ChainSync pipeline's batch permits, queue size, candidate and unpublished headers and the time since the last
  reply batch was received and handled, and the `BlockAdmissions` capacity and occupancy. The sessions keep two
  timestamps per reply batch; nothing else runs on the block path and nothing new runs periodically.
- **`--stall-dump <dir>`** (off by default) also writes the runtime's live-fiber snapshot to
  `<dir>/fiber-dump-<epoch ms>-<n>.txt` when a stall begins. The devnet scenarios that start the follower pass it, and
  `e2e-summary.json` records the events and dump files as `stallDiagnostics`.
- No behaviour changes without a stall, and no reconnect on stall.
