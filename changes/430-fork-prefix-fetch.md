### Fixed (fork switch after a partition)
- **BlockFetch decides as the reference's decision loop does, so a fork whose first blocks were declined is fetched
  once it is preferred.** After a network partition the Scala pool node could keep forging on its own short fork
  although its peers announced a longer chain, until its fork was deeper than `k` and it could never switch
  (nightly `faults`, seeds 1 and 3, #430). Requests were made only for the headers a session had just published, so
  fork headers declined while their chain was not yet longer than ours were never requested. The coordinator now
  runs ouroboros-network's `fetchLogicIterations`: it decides whenever our chain, a peer's candidate or a peer's
  fetch status changes (or the grace period expires), over each peer's whole candidate, through the reference's
  pipeline from `filterPlausibleCandidates` to `fetchRequestDecisions`, and puts each request in that peer's
  request slot for its fetch client. Publishing ChainSync headers no longer waits for their bodies, and a range's
  block budget is the length of the fragment requested.
- **The BlockFetch client keeps only the reference's limits.** A range no longer has a 120 s deadline as a whole or a
  10 MB aggregate byte cap: each message waits at most its state's limit (60 s in Busy and Streaming), and bytes are
  bounded per message and by the protocol's ingress queue, so a peer that sends a block every 50 s, or a Byron range of
  more than 10 MB, is no longer disconnected.
