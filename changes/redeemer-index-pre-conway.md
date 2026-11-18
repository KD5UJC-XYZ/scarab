### Fixed (redeemers of a repeated certificate and the order of missing redeemers)

- Before Conway, UTXOW gives a certificate that occurs again in a transaction the redeemer index
  of its first occurrence, as the reference's `getAlonzoScriptsNeeded` does (cardano-ledger
  f649f975, `Alonzo/UTxO.hs:318-361`, which Babbage uses). It had given each certificate its own
  position, so an Alonzo or Babbage transaction with two identical script-locked certificates got
  the opposite verdict: one with a redeemer at the first index only was refused with
  `MissingRedeemers`, and one with a redeemer at each position was accepted where the reference
  refuses it with `ExtraRedeemers`. Conway keeps each certificate's own position
  (`Conway/UTxO.hs:62-80`). UTXOW's needed scripts and redeemers and UTXOS's collection now come
  from the one list of the era's scripts needed.
- `MissingRedeemers` lists the purposes in the reference's order: spending, withdrawing,
  certifying, minting, then voting and proposing (`Alonzo/Rules/Utxow.hs:239-262,378-383`). It had
  listed minting before certifying and withdrawing, which changed the LocalTxSubmission refusal
  bytes but not the verdict.
