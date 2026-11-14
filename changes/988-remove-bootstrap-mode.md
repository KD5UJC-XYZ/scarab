### Removed (consensus, #988)

- The bootstrap (ledger-lite) follow mode, dead since the era-indexed rules replaced the old ones:
  its state, its lite ledger and the forger's refusal on it. The era of the last block applied and
  the epoch verdicts, from which `eraEntered` and `epochValidated` are made, are kept as the follow
  state and reported as before. TPraos header validation takes the ledger's pool distribution, as
  the reference's `TPraosLedgerView` does.

### Changed (storage, #988)

- Ledger snapshot format V10 (`SCRBSNPA`) is changed in place, with no new version: its sidecar
  holds the follow state where it held the bootstrap state. No store of V10 or V9 is deployed; a
  V10 store written by an earlier build is not supported. V9 snapshots, and older ones through V9,
  migrate on open as before, the bootstrap state's flag and lite ledger dropped and its era and
  verdicts kept.
