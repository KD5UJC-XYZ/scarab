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
