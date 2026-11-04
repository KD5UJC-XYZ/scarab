### Fixed (logging, peer selection, #460)

- `repromoteDelaySeconds` on an asynchronous `peerSelectionDemote` line is the exit policy's delay with the governor's fuzz added, as the
  reference's `TraceDemoteAsynchronous` reports it (`Governor/Monitor.hs` 150-161); it was the delay before the fuzz. A lost connection
  now reports its delay too instead of `null`. A return reason for a rollback names its point as `slot@hash` instead of the Scala
  rendering of the point.
