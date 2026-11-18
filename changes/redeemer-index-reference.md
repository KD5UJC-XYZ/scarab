### Added (redeemers of a repeated certificate)

- The `e2e` load records the reference node's verdict on the redeemer index of a repeated certificate
  and on a transaction that misses the redeemers of every purpose, accepted or refused, in Alonzo,
  Babbage and Conway (`fixtures/devnet/redeemer-index.jsonl`, with the outputs each transaction
  spends, the cost model in force and the run's provenance). `RedeemerIndexReferenceSuite` puts each
  recorded transaction to the node's pool rules: the verdicts are the reference's, and each refusal
  is written as exactly the reference's bytes.
