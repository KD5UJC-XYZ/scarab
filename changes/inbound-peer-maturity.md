### Fixed (peer sharing)

- An address learnt only from a duplex connection reaches the outbound governor's known peers, and through them
  peer-sharing answers, only once some registration of it still in force was handled by the inbound governor at
  least 900 s earlier: the inbound governor's steps mature its duplex peers (`inboundMaturePeerDelay`,
  `inactionTimeout`). Before, such a peer was offered as soon as its handshake completed. An address known from
  another source is shared by the usual criteria, as before. The steps wake on the node's own registrations and
  unregistrations and on the inactivity timeout, not on every event the reference's inbound governor steps on, an
  approved deviation: an address can become eligible somewhat earlier or later than in the reference.
- While the node listens, the peer of a connection it dials whose handshake agrees on duplex is registered with the
  inbound governor as well, for the connection's life, as the reference's connection manager announces such a
  connection; before, only the connections the node accepted were. A connection to the node itself is registered
  once, by its accepting side. The end of an accepted unidirectional connection unregisters its address too, and a
  connection whose publication the connection manager refuses is not registered.
