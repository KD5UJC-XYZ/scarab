### Removed (era types, #85)
- **The flat Conway MEMPOOL and the flat rules' Conway refusal encoder are gone.** The node has judged and answered
  Conway's transactions by Conway's era rules alone since the switch, so `rules.Mempool`, `MempoolLedger` and
  `PredicateFailureCbor` had no caller on the node's path. A Conway refusal is written from the era rules' failures
  by `ConwayFailureCbor`, the node's own refusals (a duplicate, a full pool, a validation too slow, no ledger) as
  Conway's free-text `ConwayMempoolFailure`, byte for byte as before. What is admitted, refused and sent is unchanged.
