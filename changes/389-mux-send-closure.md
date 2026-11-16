### Fixed (network, #389)

- A mini-protocol's send no longer reports the connection's closure for a message it has queued. Before, a send raced the closure against its own admission: when the peer closed the connection just after the message was queued (it was then written, and the peer had read it), the send could still raise the peer's close, and the mini-protocol stopped before reading the peer's last message (for example, a server whose last reply was followed by the client's clean close). A send now raises the closure only when its message was not queued.
