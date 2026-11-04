### Changed (Plutus context refusals, #434)

- Compare each refused context submission's whole recorded reference reason, byte for byte, rather
  than the one constructor name in its truncated `cardano-cli` text: a node-module suite replays the
  Conway transaction rule and asserts that its encoded refusal equals the reference's `ApplyTxErr`.
  Refusals that keep no raw reason stay bounded to containment until a reviewed re-recording gives
  them one.
