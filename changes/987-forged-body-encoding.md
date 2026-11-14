### Fixed (forging, #987)

- A forged block writes each of its four body parts (transaction bodies, witness sets, auxiliary data, invalid indices) with a definite length up to 23 entries and an indefinite length above, as the reference does (`variableListLenEncoding`, cardano-ledger `Encoder.hs`). A block with more than 23 entries in a part used to differ in bytes from the reference's.
