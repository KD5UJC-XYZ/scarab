### Fixed (PPUP votes counted by value, #669)

- The pre-Conway protocol-parameter quorum counts proposals by their decoded values, as the
  reference's `Ord (PParamsUpdate era)` compares them: two genesis delegates who propose the same
  values written differently (an integer with a wider head, a rational unreduced or with two
  negative integers, cost models of a language named twice) vote for one update, in the era rules
  and in the bootstrap ledger. The original bytes of each value are still what is stored and hashed.
