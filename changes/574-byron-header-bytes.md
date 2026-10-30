### Fixed (Byron header byte strings, #574)

- A Byron header's byte strings (the previous hash, the leader's key, the delegation certificate and
  signature) are read at Byron's decoder version, definite only, as the reference reads them, and its
  body proof is read as the reference reads it: a `Proof` of a main header (transaction count,
  hashes and the SSC proof) or a byte string of a boundary header. A verification key (the leader's,
  the certificate's issuer and delegate) also has its length in the shortest form (`decodeBytesCanonical`).
  A chunked string, a longer length form of a key or a malformed proof in a header was accepted before.
