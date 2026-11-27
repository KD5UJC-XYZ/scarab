### Changed (Plutus evaluation, #1499)

- Script evaluation takes less time: the scope check of a script, which walked its whole term at every evaluation,
  is made once for the decoded program the node keeps. Every result, budget, log and failure is the same as before.
