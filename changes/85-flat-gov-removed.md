### Removed (era types, #85)
- **The flat Conway GOV is gone.** Since the flat LEDGER went, only tests reached `rules.Gov` and the glue that put
  the era GOV's failures in their flat form (`ConwayGovPredFailure.toStored`, `readOf`, `GovActionNotRead`).
  `ConwayGovChecksSuite`, which compared the era GOV with it, holds the era GOV to the flat one's judgments, recorded
  before it went. Its differences from the reference went with it: the order of a refusal's votes and withdrawals, and
  its unbounded proposal index, which only a 65,537th proposal in one transaction told from the reference's `Word16`.
  What the node admits, refuses and sends is unchanged.
