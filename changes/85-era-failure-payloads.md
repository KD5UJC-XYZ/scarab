### Changed (era types, #85)

- The era-typed failures of UTXO, UTXOW, BBODY, LEDGER, CERTS and GOV, Conway's and Dijkstra's, hold the reference's `Mismatch (r :: Relation) a`, its relation a type, and the reference's widths for the collateral count (`Word16`), the collateral balances (`DeltaCoin`) and a header's highest supported major version (`Word32`), so a failure the reference's types cannot hold does not compile; their flat forms and encoded bytes are unchanged, so nothing changes in behaviour.
