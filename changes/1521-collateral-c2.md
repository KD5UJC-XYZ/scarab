### Fixed (the collateral fee under C2, #1521)

- A node could withhold a block with a phase-2-invalid transaction for good: once the JIT (C2) had compiled
  the collateral check with the sum of the outputs' values inlined, the fee a failed transaction pays came out
  null, and the rules threw (`NullPointerException ... because "that" is null`) on every retry. A restart passed
  the block. The mainnet sync from genesis stopped so at slots 120,650,699 and 124,115,106. The fee is now the
  coins of the collateral outputs less the return's, as the reference's `collAdaBalance` sums them, without
  summing whole values, which the miscompiled form needed. The fee is the same.
