### Fixed

- Align Genesis churn with the pinned network policy: complete selected startup targets,
  the default churn regime, the long interval, and all twelve actions when ledger queries
  are disabled. Preserve native raw proposals, atomically sample counters and update targets,
  wait for published counter changes, and leave targets unchanged during sleeps. Superseded
  or invalid proposals no longer replay stale queued updates.
- Keep configured public fallback peers distinct from actual big-ledger peers, including
  the reference's advertisement flags when inserting newly discovered peers.
