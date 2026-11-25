### Changed (era types, #85)
- **The ranking block's Dijkstra transactions are read in one pass, and the flat Dijkstra decoder is gone.**
  `LeiosBlock` and the Leios build's transaction submission read each Dijkstra transaction with the era decoder
  straight into Dijkstra's types; the flat block decoder's transaction, witness set and auxiliary data, their
  projection into the era's types and the flat script purposes are deleted. What is accepted and how a refusal is
  reported are unchanged, on every recorded block and transaction and on mutants of them; a value the decoder's own
  bounds rule out stays the node's defect, the block withheld and no peer charged.
