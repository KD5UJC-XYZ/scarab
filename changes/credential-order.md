### Fixed (ledger credential order)
- **`Ordering[Credential]` is the ledger's derived `Ord`: a script credential before a key one, then the hash**
  (cardano-ledger `Credential.hs:98-101`). It put the key first. No rule used it implicitly; the sites that had their own
  copy of the right order (`Voter`, the NtC query decoder, `Purpose.sortedWithdrawals`, committee members in a V3
  context) now use it, and reward accounts have the derived `Ord AccountAddress`, the network first.
- **The treasury withdrawals of a proposal in a PlutusV3 context are in the order of their reward accounts, the network
  first** (`transMap` over the `Map AccountAddress Coin`, `Conway/TxInfo.hs:671-673, 697-699`). They were ordered by the
  credential alone.
