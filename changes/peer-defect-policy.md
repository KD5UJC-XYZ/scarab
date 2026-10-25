### Fixed (security, connection error policy)

- **A decoder that throws on a connection's input closes that connection and nothing else.** The multiplexer
  runs the decoder of a mini-protocol once and raises `NetworkError.DecoderDefect(protocol, cause)`, with the
  original exception, to its caller; the node's own decode sites raise `PeerError.InputDefect(site, cause)`.
  Both are classified as a transient failure of that peer: the connection closes, the peer is tried again
  after the error delay and the node keeps running, as the reference treats an exception in a mini-protocol
  thread (`ShutdownPeer`, network `RethrowPolicy.hs`). A defect of the node itself, a storage failure and an
  internal error still stop it, with exit codes 7, 5 and 6.
