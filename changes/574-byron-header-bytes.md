### Fixed (Byron header byte strings, #574)

- A Byron header's byte strings (the previous hash, the leader's key, the delegation certificate and
  signature) are read at Byron's decoder version, definite only, as the reference reads them, and its
  body proof is read as the reference reads it: a `Proof` of a main header (transaction count,
  hashes and the SSC proof) or a byte string of a boundary header. A chunked string or a malformed
  proof in a header was accepted before.
