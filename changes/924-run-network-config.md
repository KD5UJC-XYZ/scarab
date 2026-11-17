### Added (network configuration at start-up, #924)

- A block producer (`--forge`) with `PeerSharing` set on is warned at start-up, as cardano-node 11.1.3 warns it: one
  `networkConfigUpdateWarning` line, "Mainnet block producers may not meet the Praos performance guarantees and host
  IP address will be leaked since peer sharing is enabled.", after what the reading of the topology says and before
  the peer snapshot is read. A SIGHUP reload does not say it again.

### Changed (SIGHUP reload of the topology and its peer snapshot, #924)

- A SIGHUP once the node runs installs the new topology first and then reads its `peerSnapshotFile` again on its own,
  as cardano-node 11.1.3 does. A snapshot file it cannot read in `GenesisMode`, or one of a legacy version, at the
  origin or of another network, no longer refuses the whole reload: the new topology is used with no snapshot, and,
  as on the reference, nothing is logged. A snapshot older than `useLedgerAfterSlot` keeps the snapshot in force under
  the new topology, with a warning. A SIGHUP during start-up installs the new topology and keeps the snapshot in
  force on any of these.
- A SIGHUP during start-up whose topology or snapshot is refused no longer logs `blockForgingUpdate` `NotEffective`,
  as the reference's handler stops before it.
- At a reload the new topology is installed, once, before the snapshot file is read, as on the reference. The
  snapshot read is then written to what the ledger-peers worker reads, which takes it at its next rebuild of the
  stake maps, read together with the ledger's slot and peers, with no new root generation; in `GenesisMode` a
  snapshot at another block point is checked against the immutable chain again, and a check still running for the
  snapshot it replaced reports its verdict too, as on the reference. The `ledgerPeers` lines `snapshotLoaded` and `snapshotIgnored` are logged where the file is
  read, at start-up and at a reload, and no longer each time a topology is installed, so a snapshot kept or dropped by
  a reload is not said to be loaded.
