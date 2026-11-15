### Added (node-to-client reward provenance, #924)

- LocalStateQuery answers `GetRewardProvenance` (block query tag 14), and its `GetCBOR` form, in every
  Shelley-based era at every node-to-client version, as the reference does: with the default reward
  provenance whatever the state, written at the era's encoding version (its two rationals untagged
  before Conway and under tag 30 in Conway). The query used to close the connection. The answers equal
  those of cardano-node 11.1.3 recorded in each era from Shelley to Conway, and the `ntc` scenario
  compares both forms with the reference node's in Conway.
