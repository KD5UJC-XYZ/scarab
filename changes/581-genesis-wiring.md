### Fixed (Genesis, #581)
- **A Genesis invariant met by a session's header gate ends that connection, not the node.** The clock wrapper around
  every live session's header check dropped the connection's invariant channel (`HeaderClock.clocked` did not forward
  `withInvariant`). Such an invariant therefore went to the node-wide channel and stopped the node.

### Changed (Genesis, #581)
- **The Genesis node's wiring is fixed when it is built.**
  - The Genesis controls take the shared block pool itself, not an option of it.
  - Only the node's own header check can graft a jump's headers (`HeaderCheck.Grafting`). A Genesis runtime is built
    with it, and each session uses the check its controls hand it.
  - `GenesisInternalProblem.MissingSharedBlockPool` and `UnsupportedHeaderFamily`, which stopped the node, are gone.
  - A jump accepted before the session started a gate ends that connection, uncharged.
