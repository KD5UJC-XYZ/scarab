### Changed (era types, #85)

- The script-context classes on the era types take the shape of cardano-ledger 9df9f98c (the Leios testnet's build) and 29a7d656 (cardano-node 11.2.1): the transaction's information is of a level and holds the scripts used and, at the top level, the room for each sub-transaction's information, which Dijkstra's contexts will fill. Conway's contexts, arguments, collections and refusals are unchanged, as the suites that compare them with the reference's recorded contexts and the existing path show. The node still builds contexts with the existing path.
