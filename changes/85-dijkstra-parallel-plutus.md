### Changed (performance, #85)

- Under `--reference-build leios-rc-2026w47`, a Dijkstra block's Plutus scripts run in the node's bounded
  workers (`--plutus-workers`), as the other eras' do (ADR 0018). Each transaction is one job: its scripts at the
  top level and in its sub-transactions. Every result is the serial run's: the same state, the same failures, the
  same phase-2 counters, and the same naming of a script the evaluator cannot judge, the sub-transaction's
  included. Where a script's verdict is not the one the transaction's flag announces, the serial rules give the
  result. A Dijkstra block's header is still checked after its body, as before.
- `LeiosReplayMain` takes `--plutus-workers N` and prints the count it replays with.
