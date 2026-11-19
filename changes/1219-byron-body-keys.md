### Fixed (ledger, #1219)

- A fetched Byron block whose body holds a verification key (a transaction witness's, a delegation certificate's
  issuer or delegate, an update proposal's issuer, a vote's voter) with its length not written in its shortest form
  is now refused as a body that does not decode, as the reference refuses it; such a block was accepted before.
