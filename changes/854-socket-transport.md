### Changed (node-to-node sockets, #854)
- **Node-to-node TCP sockets are configured as the reference configures them** (ouroboros-network `2fb69829`,
  `Socket.hs` 148-170, `ConnectionManager/Core.hs` 1472-1491).
  - **Options:** the listener sets `SO_REUSEADDR` and `SO_REUSEPORT`; outbound and accepted sockets also set
    `TCP_NODELAY` and linger 0. Sockets are opened in the family of their address. A closed connection is reset, not ended with a FIN, and
    keeps no TIME_WAIT. The listen backlog is 8.
  - **Bind:** an outbound connection in `InitiatorAndResponder` mode is bound to the address the node listens on
    (`--listen`), so a peer sees it come from that address. A peer that dialled us from its own listening address and
    our connection to it are then one TCP connection, which the connection manager reuses.
  - **A node that dials its own address** is connected to itself and negotiates with itself over that one connection,
    as the reference is on Linux.
  - **Errors:** a failed connect, bind or accept is a typed socket error with its errno; the log shows it as the
    reference's `IOError` text, without the socket's descriptor (`connect 10.0.0.1:3001: Network.Socket.connect: does
    not exist (Connection refused)`). A listener that cannot be set up, or whose accept loop stops, stops the node with
    `DiffusionErrored`.
  - **`--listen` with an IPv6 address** is refused at start-up, before any socket is opened (an approved deviation until
    `IPV6_V6ONLY` can be set, #859). Its host is resolved once at start-up.
