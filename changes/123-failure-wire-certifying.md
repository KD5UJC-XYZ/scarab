### Fixed (failure encodings, #123)

- A missing redeemer of a certifying purpose is written with its certificate in Conway; it was written as
  null.

### Added (failure encodings, #123)

- Alonzo and Babbage answer a refused transaction with the structured failure when it is a missing redeemer
  of an unregistration or a delegation by a script credential, or a collect error: no redeemer (a script
  purpose, which for a certificate is that certificate), no witness, no cost model, or a translation fault.
  Alonzo has one translation constructor (a missing input); Babbage has its six (the Byron output, the
  unresolved input, the redeemer pointer and the inline datum, reference script and reference input
  constructors). Other certificates and the remaining translation faults still have no encoding in those eras.

### Verified (failure encodings, #123)

- The e2e load provokes the node's refusal of a script credential's delegation and unregistration without a
  redeemer, of a spend without a redeemer or without its script, of a Plutus V1 script given a reference
  script, inline datum or unresolvable input, of a missing key witness and of a small output, in Alonzo,
  Babbage and Conway, and keeps the raw reasons in `fixtures/devnet/refused-raw-plutus.jsonl` (with the
  commit, command, image and protocol version of the run). The 46 encodings are byte-equal to the node's
  (`PlutusFailureReferenceSuite`); the Babbage version-7 write assumption holds. `NoWitness` and
  `NoCostModel` cannot be provoked and stay hand-derived.
