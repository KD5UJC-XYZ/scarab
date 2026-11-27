### Removed (era types, #85)
- **The flat Conway UTXO, UTXOW and UTXOS are gone.** Since the flat LEDGER, GOV and CERTS went, only tests reached
  Conway's flat `Utxo`, the transition of the flat `Utxow`, `Utxos.sts` and `Deposits.of`, and the glue that put the era
  UTXO and UTXOW failures in their flat form (`ConwayUtxoPredFailure.toStored`, `ConwayUtxowPredFailure.toStored`,
  `UtxowUnprojected`). The suites that compared the era rules with them hold the era rules to their judgments,
  recorded before they went (`fixtures/conway-flat-refusals`). What the eras before Conway run stays: UTXOW's checks,
  the evaluation of the scripts and the flat script contexts of every language, which their collection shares. The
  case files of `--plutus-cases` keep naming a Conway collection's failures as the flat collection named them, their
  bytes pinned. What the node admits, refuses and sends is unchanged.
