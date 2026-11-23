### Added (tools, #79)

- `tools` `TxSubmitPeer` hands signed transactions (the text envelopes `cardano-cli ... transaction sign`
  writes) to one peer over node-to-node, as a relay passes transactions on: the handshake as an
  initiator-only peer, KeepAlive, and TxSubmission2 offering them in order until the peer has asked for
  every body. It sends a test load into the network through a pool other than the node under test, so
  the load reaches that node by diffusion only.
