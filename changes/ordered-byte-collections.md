### Fixed (decoding and the ledger rules, sets and maps of byte strings)

- The decoders of transactions and block bodies from Shelley to Dijkstra find repeated elements and
  keys by sorting, as the reference's `Set.fromList` and `Map.fromList` do, and the set arguments of
  a local state query are read into sorted sets. What a witness set, an ordered set of certificates
  or proposals, the keys of a body's auxiliary data or a query's set costs no longer depends on the
  hash codes of the byte strings in it: byte strings that share one hash code made each of these
  cost the square of their number, from seconds to minutes for a body or a query of a few hundred
  kilobytes. A multi-asset value's repeated policies and names are found by sorting too; below
  decoder version 9 the entries it drops (repeats, quantities of zero, policies left empty) are
  dropped before its maps are built, which come out as before; and a value too big to compact is
  refused before them. The verdicts and the refusals are unchanged.
- The ledger rules sum the values a transaction spends and produces, and its collateral, in sorted
  maps, so the time a transaction holds the mempool does not depend on the asset names in the outputs
  it spends. The values the UTxO set holds, and the bytes the stores write, are unchanged.
