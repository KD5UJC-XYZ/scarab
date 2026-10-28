### Changed (network, #463)

- An exception the multiplexer's bearer reader throws while it reads or routes the peer's bytes closes that connection, with the cause kept (`NetworkError.BearerDefect`, a `PeerError.InputDefect` at the bearer), instead of stopping the node with exit code 7: the reference's policy for an exception in a bearer thread is `ShutdownPeer` (ouroboros-network `RethrowPolicy.hs`, `ConnectionHandler.hs`). A transport `IOException` stays a closed connection.
