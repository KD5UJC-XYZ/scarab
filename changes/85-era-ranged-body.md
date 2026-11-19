### Changed (era types, #85)

- The required signers' field of a transaction body is a class of its own on the era types (`AlonzoEraTxBodyToConway`), meant for the eras Alonzo to Conway, as the reference confines `reqSignerHashesTxBodyL` to them; its only instance yet is Conway's, the one era with a typed body, and Dijkstra's body will have none. The required signers of every era stay on `AlonzoEraTxBody`: the field up to Conway, and in the reference Dijkstra's guards' key hashes, which have no typed instance yet. The node still checks transactions with the existing rules.
