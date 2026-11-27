### Fixed (ledger, #1495)

- An enacted Alonzo or Babbage protocol-parameter update replaces the cost models in force with the
  ones it proposes, as the reference's default `applyPPUpdates` does. It used to merge them, so a
  language the update did not name stayed in Scarab's parameters but was gone from the reference's: a
  script of that language could pass phase 2 in Scarab and fail in the reference, and the two chains
  could split. Conway and Dijkstra still merge, as the reference does. The snapshot format does not
  change.
