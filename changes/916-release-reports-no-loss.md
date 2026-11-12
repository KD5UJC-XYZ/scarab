### Fixed (networking, #916)
- **Stopping the node no longer reports its outbound connections lost.** Each connection's monitor ran
  under the same supervisor as the connection, so the release of the peer network cancelled both at once:
  under some fiber orders the monitor saw its connection end first, logged a `reconnecting` event and
  counted it in `scarab_peer_reconnects_total` for a peer the node was itself letting go. The monitors now
  have a scope of their own, released before the connections, as the reference closes the job pool of its
  peer state actions inside the scope of its connection manager.
