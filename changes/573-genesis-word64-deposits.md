### Fixed (genesis and ledger quantities out of range, #573)
- **A Shelley genesis whose fees, deposits or minimums are negative or above `Word64` is refused at start-up, as the
  reference refuses it.** `minFeeA`, `minFeeB`, `keyDeposit`, `poolDeposit`, `minUTxOValue` and `minPoolCost` were read
  as unbounded integers; the reference reads them into `CompactForm Coin` (`FromJSON Word64`). Such a file used to start,
  and a deposit then surfaced as a quantity out of range at the first certificate.
- **A Conway genesis deposit outside `Word64` now ends the upgrade into Conway as a quantity out of range, where the
  reference's `compactCoinOrError` aborts,** instead of surfacing at the first DRep registration. As before, the node
  does not crash: the block is not adopted and the node goes on.
- The genesis-fields oracle corpus gains 22 reference verdicts for these values (301 cases).
