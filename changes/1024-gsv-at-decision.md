### Changed (performance, #1024)

- Each BlockFetch decision reads every peer connection's GSV estimate itself, in its snapshot, as the reference reads the peers' GSVs. The per-peer loop that copied the estimate into the decision state every 10 ms, about 100 timers a second for each peer, is gone. A decision now uses the estimate as it is when it decides, not a copy up to 10 ms old; a new estimate still starts no decision, and an older sample still never replaces a newer one.
