### Fixed (forging, #769)

- A forged header carries the node's protocol version (`cardanoProtocolVersion`), not the ticked
  ledger's: 11.2, or 12.0 with `ExperimentalHardForksEnabled`, as cardano-node 11.1.3 sets it
  (`Cardano.Node.Protocol.Cardano` lines 192-194; ouroboros-consensus 82ecba32
  `Shelley/Ledger/Forge.hs:83`). A node on a ledger at version 10 now signals readiness for 11, as
  the reference does.
