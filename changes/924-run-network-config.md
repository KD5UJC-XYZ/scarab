### Added (network configuration at start-up, #924)

- A block producer (`--forge`) with `PeerSharing` set on is warned at start-up, as cardano-node 11.1.3 warns it: one
  `networkConfigUpdateWarning` line, "Mainnet block producers may not meet the Praos performance guarantees and host
  IP address will be leaked since peer sharing is enabled.", after what the reading of the topology says and before
  the peer snapshot is read. A SIGHUP reload does not say it again.
