### Fixed (node-to-client refusals, #832)
- **A transaction output whose bootstrap address carries more than 64 bytes of attributes is now refused with the
  failure 11.1.3 reports.** From Allegra on, the reference injects Shelley's `OutputBootAddrAttrsTooBig` through
  `shelleyToAllegraUtxoPredFailure`, which makes it `OutputTooBigUTxO` (cardano-ledger f649f975,
  `Allegra/Rules/Utxo.hs:387`); Alonzo, Babbage and Conway give each output the sizes 0 and 0. Scarab reported
  `OutputBootAddrAttrsTooBig` in every era, so its node-to-client refusal bytes differed from the reference's in
  Allegra, Mary, Alonzo, Babbage and Conway (Conway: tag 10 where the reference writes tag 11). The verdict was
  always the same. Shelley keeps its own failure.
- **The ledger rules now know the reference build they follow.** `--reference-build` reaches the rules
  (`LedgerGlobals.referenceBuild`, `TxEnv.referenceBuild`). The bootstrap-attribute failure is the first rule that
  matches on it: the Leios build (`leios-b004fe0c`, cardano-ledger 9df9f98c) keeps `OutputBootAddrAttrsTooBig` in every
  era, as that build does.
