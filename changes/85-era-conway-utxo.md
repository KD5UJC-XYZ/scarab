### Added (era types, #85)

- The first predicates of Conway's UTXO rule run on the era types (`ledger/era/rules`): the input, reference-input, validity-interval, network and collateral-count checks, each the reference's function generic in its era over its classes. Their verdicts equal the existing rule's on every recorded Conway refusal, and on crafted cases for the three the corpus never refuses. The node still judges with the existing rule, so behaviour does not change.
