### Added (era types, #85)

- Alonzo has its state classes, PPUP and UTXOS on the era types:
  - its accounts, certificate state, governance state, instant stake and stored outputs are Babbage's classes at
    Alonzo, over the records the node keeps;
  - UTXOS evaluates a valid transaction's scripts before PPUP, does not record a failure to build a script
    context, and takes an invalid transaction's collateral whole as fees, as the reference does at Alonzo.

  On the recorded Alonzo states and submissions, the refusal corpora's cases with their states, the era-walking
  capture's proposals and crafted cases, they give the flat rules' verdicts, failures and states, and the
  reference's verdicts, but for a failure to build a context, which the flat rules record and no transaction the
  reference accepts reaches. The node does not run them yet, so neither behaviour nor the persisted format changes.
