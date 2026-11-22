### Changed (era types, #85)

- The era-typed failures of UTXO, UTXOW, BBODY, LEDGER, CERTS and GOV, Conway's and Dijkstra's, hold the reference's `Mismatch (r :: Relation) a`, its relation a type, so a failure with another relation than the reference's does not compile. The collateral count holds the reference's `Word16`, the collateral balances its `DeltaCoin`, and a header's highest supported major version its `Word32`; the other payloads keep their types for now. Their flat forms and encoded bytes are unchanged, so nothing changes in behaviour.
