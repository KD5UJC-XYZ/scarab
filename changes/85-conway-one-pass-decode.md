### Changed (era types, #85)
- **Conway's era decoders read a transaction in one pass.** `ConwayTxBody.decode`, `ConwayTxWits.decode`,
  `ConwayTxAuxData.decode`, `ConwayTx.decode` and `ConwayTx.fromEnvelope` parse each part's bytes once (again only to recover a detached
  record the plain parse refuses) and read its fields once into Conway's types, instead of the flat decoder's reading followed by a projection that parsed the
  witness set and the auxiliary data again and read each output's datum and reference script, each native script and
  each datum and redeemer again. What is accepted, the bytes, ids and stored outputs kept, and the first refusal
  reported are unchanged: a differential suite and a fuzz target hold the one pass to the two passes on every recorded
  Conway transaction and part and on mutants of them, and a suite holds it to the reference's verdicts on the recorded
  reference corpora. Measured on the decoders alone, reading the recorded Conway transactions in their block form or
  with `ConwayTx.fromEnvelope` takes about 0.67 of the time and 0.7 of the allocation it did; on the route the node's
  pool takes for a submitted transaction, whose flat reading stays, about 0.82 of the time and 0.8 of the allocation. A
  submission's whole path through the node was not measured.
