### Removed (era types, #85)
- **The flat Conway GOV is gone.** Since the flat LEDGER went, only tests reached `rules.Gov` and the glue that put
  the era GOV's failures in their flat form (`ConwayGovPredFailure.toStored`, `readOf`, `GovActionNotRead`).
  `ConwayGovChecksSuite`, which compared the era GOV with it, holds the era GOV to the flat one's judgments, recorded
  before it went. Its one difference from the reference, the order of a refusal's votes and withdrawals, went with it.
  What the node admits, refuses and sends is unchanged.
