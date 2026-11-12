### Fixed (networking, #914)
- **A shutdown no longer reports the node's own outbound connections as lost.** The monitors of the outbound
  connections and the connections themselves were ended together when the peer network closed (after a fatal
  error, or on a signal), so a monitor could see its connection aborted by that close and report it as lost: a
  `reconnecting` event with `BearerClosed(Aborted)`, counted in the reconnect metric, while the node was stopping.
  The monitors now run in a scope inside the connections' scope and end before any connection is closed, as the
  reference's monitoring loops are jobs of `withPeerStateActions`, inside the connection manager whose exit
  cancels the connection threads.
