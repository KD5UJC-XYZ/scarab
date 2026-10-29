### Fixed (networking, #597)
- **A peer whose block body does not match its header is charged with the protocol fault again.** When a
  hot connection's BlockFetch client met the peer's fault, ChainSync, cancelled after it, reported a plain
  bearer abort, and the connection monitor took that first: the peer was backed off as transiently
  unavailable instead of forgotten for breaking the protocol. A hot protocol cancelled after the connection
  failed now reports that failure, as the reference's mux completes it with `Shutdown (Just e)`.
