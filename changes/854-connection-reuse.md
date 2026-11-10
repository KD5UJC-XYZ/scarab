### Changed (connection reuse, #854)
- **One connection serves both sides of a peer, as in the reference's connection manager** (ouroboros-network
  `2fb69829`, `ConnectionManager/Core.hs`).
  - **Reuse:** when the outbound governor promotes a peer that has a duplex connection to us, our clients run on that
    connection, with no dial and no second handshake. The connection log shows the move (`InboundIdleSt Duplex` to
    `OutboundDupSt Ticking`, or `InboundSt Duplex` to `DuplexSt`).
  - **Refusals:** a peer's unidirectional connection, an outbound idle one, and a peer behind a firewall that is not
    connected are refused with the reference's typed errors; the last is never dialled.
  - **Waits:** an acquisition waits for a negotiating connection's handshake and for a connection in time-wait, where it
    used to be refused.
  - **Release:** releasing our side of a connection the peer still uses leaves it open. An outbound connection nobody uses
    is closed after the outbound idle timeout (5 s). A peer is cold once our side holds its connection no more.
  - **The peer's activity:** it is tracked on every duplex connection, ours included. When the peer stays idle for the
    protocol idle timeout (5 s), a connection we still use is kept and an unused one is closed. A responder that
    starts as the timer expires is held until that is settled, and then wakes the connection.
  - **Pruning:** a remote promotion or an outbound release that would exceed the hard limit of accepted connections
    prunes inbound connections, upstream ones last (`connection` `pruned` log line, `Pruned` in
    `scarab_connection_events_total`).
  - **Handshake failure:** a connection whose handshake agreed no version leaves the table at once and holds no slot in
    time-wait. Every acquisition that waited for it fails with the handshake's own error.
  - **`GET /connections`** reports `sessions`: for each peer, its running hot session (`active`: the connection it runs
    over, its activation, its intersection, the headers received and the tip it announced) and totals over all its
    sessions (`lifetime`). Each entry of `connections` has its `connection` id.

### Removed (connection reuse, #854)
- **`--inbound-sync` is removed.** Our clients run only where the outbound governor promotes a peer. The inbound governor
  only serves accepted connections.
  - `GET /connections` no longer reports `inboundSessions`.
  - The `inbound` log events `following`, `sessionEnded`, `sessionDropped`, `sessionReturned` and
    `cachedBlockRejected`, and their `scarab_inbound_events_total` labels, are gone.

### Fixed (peer governor, #854)
- **A topology reload no longer blinds the governor to the connections it kept.** It used to drop what their monitors
  observed, so a hot peer whose session had ended stayed hot in its view and was never activated again.
