### Fixed (Genesis)
- **An invariant of the node's own Genesis code that does not hold for one connection no longer stops the node.** Such
  an invariant concerns a connection's TCP identity, the patience watcher, the jumper's state, or a header's time,
  state or selection view. It now ends that connection as `GenesisInvariant`, and the peer is not charged, as the
  reference's `ourBug` is `ShutdownPeer`. Genesis without the shared block pool, or with a header gate that cannot
  graft, still stops the node: these are the node's own wiring (issue 581 tracks refusing them at startup). Part of
  issue 567 (H7).
