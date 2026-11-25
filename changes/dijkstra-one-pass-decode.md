### Changed (era types, #85)
- **Dijkstra's era decoder reads a transaction in one pass.** `DijkstraTx.inBlock` and `DijkstraTx.fromSubmission` read
  the body, the witness set and the auxiliary data from the transaction's item straight into Dijkstra's types, instead of
  the flat block decoder's transaction followed by a projection that read each output, native script, metadatum, datum
  and redeemer again from its bytes. What is accepted and how a refusal is reported are unchanged: a differential suite
  and a fuzz target hold the one pass to the two on every recorded Dijkstra transaction and on mutants of them. Reading
  the recorded endorser block closure takes about 0.6 of the time and of the allocation it did.
