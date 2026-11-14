### Fixed (bootstrap witnesses of one address root)
- **Bootstrap witnesses with the same address root are one witness, the last read, as in the reference's witness set.**
  The reference reads bootstrap witnesses into a `Set` whose `Ord` compares the address root (`bootstrapWitKeyHash`)
  alone (cardano-ledger `Keys/Bootstrap.hs:108-109`; `Shelley/TxWits.hs:246-249`, `Alonzo/TxWits.hs:613-668`), keeps
  the last of those with one root, and checks only its signature (`validateVerifiedWits`, `Shelley/Rules/Utxow.hs:391-407`).
  Scarab kept each distinct witness and checked every signature. The witness set decoder of every era from Shelley to
  Conway now keeps one witness per root, the last read, in the order of the roots. `fixtures/reference/bootstrap-duplicates`
  holds 312 cases recorded from the unchanged released decoders and signature check.
