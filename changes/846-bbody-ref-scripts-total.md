### Changed (ledger, #846)

- **A block whose reference scripts exceed the Conway limit reports their total as well as the limit**, as the reference's `BodyRefScriptsSizeTooBig` does (a `Mismatch` of supplied and expected, each a 64-bit `Int`). The log reason now reads "reference scripts of the block total N bytes, over the limit of 1048576".
