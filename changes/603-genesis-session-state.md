### Changed (genesis, #603, #593)
- **A Genesis session holds its own header state, as the reference's ChainSync client does.** The session keeps the gate
  it starts at its intersection and hands it to the Genesis controls when it observes headers, restarts or accepts a
  jump; the runtime keeps no gate, only the history the session last published. It takes jump instructions only once
  it holds its gate, so a jump never meets a session without one. A session takes its header check and its Genesis controls as one value (`Peer.Chain`).
