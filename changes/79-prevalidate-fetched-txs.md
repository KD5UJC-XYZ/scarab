### Changed (Leios voting, #79)

- A voting node pre-validates the transactions a peer sends for an endorser block's closure as they
  arrive: those whose every input is on the chain, in the tip's epoch, are validated in parallel on
  the tip's state and, when they pass, recorded in the transaction cache as validated, so the closure
  check before the vote reapplies them instead of validating them in full inside the vote window.
  The closure's verdict is unchanged. New log lines `leiosTxsPrevalidated` and
  `leiosPrevalidationSkipped`.
