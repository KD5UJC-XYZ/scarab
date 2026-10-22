### Fixed

- Exclude local body consumers, journal writes and publication backpressure from BlockFetch's
  whole-request budget, including requests queued behind a callback. Peer silence and per-state
  wire limits remain bounded.
- Complete ChainSync jumping state changes and their wakeups across cancellation, and always clear
  the legacy ChainSync client's busy flag when a request raises or is canceled.
- Run the patience watcher in its own typed error scope and report callback failures and defects
  to its resource owner's mandatory failure handler.
