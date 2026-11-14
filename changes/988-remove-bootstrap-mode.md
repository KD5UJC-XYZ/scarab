### Changed (storage, #988)

- Ledger snapshots are format V11 (`SCRBSNPB`): the V10 layout whose sidecar holds the follow state
  (the era of the last block applied and the leader-check verdicts of the current and the last
  completed epoch) where V10's held the bootstrap (ledger-lite) state. V10 snapshots, and older ones
  through V10, migrate on open with their recorded build, checkpoint binding and Haskell state bytes
  unchanged; the bootstrap state's flag, which no build since the era-indexed rules set, and its
  lite ledger, which none read, are dropped, and its era and verdicts are kept, so `eraEntered` and
  `epochValidated` are reported as before. `export-ledger-snapshot`, `export-scls` and
  `import-scls` read a V10 file of a database not yet opened, as they read a V9 one.

### Removed (consensus, #988)

- The bootstrap (ledger-lite) follow mode, dead since the era-indexed rules replaced the old ones:
  its state, its lite ledger and the forger's refusal on it. TPraos header validation takes the
  ledger's pool distribution, as the reference's `TPraosLedgerView` does.
