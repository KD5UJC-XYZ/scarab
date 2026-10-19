### Added (parallel Plutus, #21)

- Evaluate Plutus transaction jobs in bounded dependency batches after ordered ledger preparation.
  The node shares up to four workers by default; `--plutus-workers 1` selects exact serial validation.
  Ledger state, typed failure precedence and recorded observations retain transaction order.
