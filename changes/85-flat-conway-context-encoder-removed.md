### Removed (era types, #85)
- **The flat Conway context error encoder is gone.** Since the Conway switch no node answer wrote a Conway
  `CollectError` or `ContextError` with `ContextFailureCbor`; only `ContextMatrixSuite` did. Its Conway arms (the
  wrapped Babbage errors, Conway's own from tag 9, its certificates and proposals in a purpose) went, with the
  shared purpose writer (`FailureCbor.item`) whose voting and proposing arms only they reached, and the encoder
  writes Alonzo's and Babbage's alone. The matrix's 115 refused Conway collections are held to the
  reference's bytes through Conway's era collection and its era encoder (`ConwayFailureCbor`) instead. What the
  node admits, refuses and sends is unchanged.
