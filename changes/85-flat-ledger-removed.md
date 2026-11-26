### Removed (era types, #85)
- **The flat Conway LEDGER and LEDGERS are gone.** Since the flat MEMPOOL and BBODY went, only tests reached
  `rules.Ledger`, `rules.Ledgers` and the glue that put the era LEDGER's failures in their flat form
  (`ConwayLedgerPredFailure.toStored`). The suites that ran them judge Conway's era LEDGER on true Conway bytes, and
  those that compared it with the flat one hold it to the flat one's judgments, recorded before it went. What the
  node admits, refuses and sends is unchanged.
