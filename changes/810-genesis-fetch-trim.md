### Fixed (block fetch, #810)
- **A Genesis BlockFetch decision no longer costs the product of the dynamo's candidate and the blocks to fetch
  when the dynamo is behind the best candidate.** Trimming the blocks to fetch to the serving peer's candidate
  looked for each of them by a scan of that candidate; the candidate's points are now put in one set per decision.
  With an 8,000-header best candidate and a dynamo 2,000 headers behind it, a decision fell from about 37 ms of
  CPU to about 1.4 ms. What the decision requests is unchanged.
