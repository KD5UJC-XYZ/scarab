### Changed (Leios forged protocol version, #54)

- Under `--reference-build leios-b004fe0c` the protocol version the node's forged headers carry is
  that build's (`cardanoProtocolVersion`, cardano-node b004fe0c): 12.0 with
  `ExperimentalHardForksEnabled`, as the public Leios testnet sets it, and 11.0 without. The
  release builds keep 12.0 and 11.2, and the maximum major protocol version of the Leios build is
  read from the same value.
