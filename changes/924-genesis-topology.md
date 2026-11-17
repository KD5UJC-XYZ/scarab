### Changed (topology in Genesis mode, #924)

- In `GenesisMode` a topology's `bootstrapPeers` no longer stop the node at start: as cardano-node 11.1.3 does, the
  node logs the reference's warning (`networkConfigUpdateWarning`) and goes on without them, from big ledger peers, so
  the official mainnet, preprod and preview configuration and topology run as published (with the
  `peer-snapshot.json` they name). The same holds at a SIGHUP reload.
- The node logs the reference's other advice on a topology, at start-up and at each reload
  (`networkConfigUpdateWarning`, `networkConfigUpdateInfo`): ledger peers on a block producer, a peer snapshot with
  ledger peers off, and in `GenesisMode` a relay without ledger peers or without a peer snapshot.
- A topology that names no root is no longer refused (the reference does not refuse one); an empty `bootstrapPeers`
  now needs a trustable local root group that names at least one access point, as the reference requires.
