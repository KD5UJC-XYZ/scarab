### Fixed (configuration)

- A cardano-config envelope (the configuration form cardano-node 11.2.1 ships, with every setting
  nested under a top-level `Configuration` key) is refused with `ConfigError` `CardanoConfigEnvelope`
  instead of being read as an empty configuration: in a `--genesis-dir` directory under every
  reference build, and with `--config` under `--reference-build 11.2` and `leios-b004fe0c`, which tell
  the form apart by that key as their nodes do. Under the default `11.1` build a `--config` file is
  read flat as cardano-node 11.1.3 reads it, which refuses an envelope for its missing
  `ByronGenesisFile`. The node reads only the flat form yet; the flat configuration of the network
  serves both cardano-node 11.1.3 and 11.2.1.

### Added (configuration)

- A `--genesis-dir` configuration's top-level keys that the node does not read are named at start in
  a `configKeysIgnored` warning; a `--config` file reports none, as cardano-node 11.1.3 reports none.
