### Fixed (devnet ledger comparison, #355)

- **A Conway ledger comparison that cannot be bracketed now says why.** `compare_once_detailed` returns, for each attempt that
  failed, either the reference query that failed (exit status, stderr tail) or the two tip slots that moved while the queries ran;
  `compare_era` records the last three and the count in the comparison output. A comparison that cannot be taken is still an error.
