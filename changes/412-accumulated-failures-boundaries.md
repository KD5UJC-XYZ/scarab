### Changed (rule boundaries, #412)

- The rules of a transaction record their predicate failures through the judgment, and every
  reference `trans` is a sub-rule that always runs: MEMPOOL, LEDGER, CERTS and DELEGS (by recursion
  on the prefix of the certificates), CERT and DELPL, UTXOW, UTXO, UTXOS and PPUP, placed as each era
  places it. A transaction submitted over LocalTxSubmission is judged by the ledger's MEMPOOL rule and
  its refusal lists the failures of every rule that refused it, in the reference's order; within a
  rule the checks still stop at the first failure until the rule records. Plutus scripts are not
  evaluated once something has failed (`whenFailureFree`). Blocks keep their first-failure verdict.
