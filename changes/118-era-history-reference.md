### Fixed (node-to-client era history, #118)
- **`GetInterpreter` on a chain still in Byron ends Byron at its safe zone, as the reference does.** The answer
  placed the end of Byron where the configuration alone puts Shelley (a million epochs out), so
  `cardano-cli query tip` and wallets read a wrong era history while a node was syncing Byron. The answer is
  now the reference's `reconstructSummary` of the state's own eras and transition, the history the header
  clock already used, so a transition that is already known also ends the current era there and lists the
  next era.
- `EraHistoryNetworksSuite` compares with answers captured from the unchanged ouroboros-consensus
  (`fixtures/reference/era-history`), no longer with bytes written by hand.
- **In Byron, a stable update to Shelley's protocol version makes the transition known (#804).** As the
  reference's `byronTransition` does, the era history then ends Byron at the update's adoption epoch and lists
  Shelley. This holds for `GetInterpreter` and for the header clock's horizon in Byron's last epochs.
