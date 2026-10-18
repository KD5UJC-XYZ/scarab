### Fixed (Plutus script contexts, #177)

- Match the reference's optional spending datum: missing witnesses produce two legacy arguments or
  V3 spending info without a datum. Phase1 continues to reject missing required datum hashes for
  every language and either validity flag; 18 complete private reference cases retain the verdicts.
