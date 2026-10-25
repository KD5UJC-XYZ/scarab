### Changed (UTXOW and UTXOS, #412)

- UTXOW records every failing check, in the order of each era's reference rule: Shelley to Mary
  (`transitionRulesUTXOW`), Alonzo (`alonzoStyleWitness`), and Babbage and Conway
  (`babbageUtxowTransition`, after the MIR quorum in Babbage). The datums are checked before the
  redeemers and the extra redeemers before the missing ones; both malformed-script failures, both
  metadata failures and the script integrity hash (now one check in UTXOW, as the reference's
  `checkScriptIntegrityHash`) are reported; before Babbage every native script provided must
  hold, from Babbage every needed one (#334).
- UTXOW no longer checks first that the inputs exist: its checks read the inputs the UTxO set holds,
  and UTXO reports the others.
- UTXOS records its failures, and a transaction flagged invalid with no script to fail is refused
  there (`PassedUnexpectedly`), when nothing failed before, as the reference does.
