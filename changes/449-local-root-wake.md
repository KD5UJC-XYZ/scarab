### Fixed (local roots, #449)
- **A topology reload no longer has its new local-root DNS lookups cancelled by a stale wake.** The local-root
  refresh loop restarted on every wake, so a wake that arrived after the loop had already picked up the reloaded
  generation cancelled and restarted that generation's lookups. As the reference's `localRootPeersProvider` does,
  the loop now restarts only for a configuration it is not already resolving. The reload test runs 1000 controlled
  schedules; five of them failed before.
