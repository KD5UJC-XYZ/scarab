### Added (era types, #85)

- The first predicates of Conway's UTXO rule run on the era types (`ledger/era/rules`): the input, reference-input, validity-interval, network and collateral-count checks, each the reference's function generic in its era over its classes. Their verdicts equal the existing rule's on every recorded Conway refusal, and on crafted cases for the three the corpus never refuses. The node still judges with the existing rule, so the typed predicates change no behaviour (the fix below does). As in the existing rule, a wrong-network refusal lists each output address as received, where the reference lists decoded addresses re-encoded (#796).

### Fixed (UTXO, #85)

- A transaction's collateral inputs are counted as the reference counts them for the maximum (`validateTooManyCollateralInputs`): the size of their set narrowed to a `Word16`, so modulo 65,536, in Conway's and Dijkstra's UTXO. A set of 65,536 or more inputs was refused where the reference compares its count modulo 65,536.
