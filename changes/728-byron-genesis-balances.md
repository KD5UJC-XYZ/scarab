### Fixed (ledger, #728)

- A Byron genesis non-AVVM balance's address is decoded whole, as `decodeAddressBase58` decodes it: a genesis whose address is not a Byron address is refused with a typed configuration error, and the genesis UTxO keys and holds each address encoded again minimally, as the reference's `fromBalances` does.
- Two balances of one address or one AVVM key keep the first in the file, as the reference's canonical JSON map does.

### Fixed (ledger, #729)

- A Byron genesis balance's amount is read as Haskell's `Read Word64` reads it (modulo 2^64; hex, octal, whitespace and parentheses taken; `+`, decimals and exponents refused), and an AVVM key as `fromAvvmVK` reads it (either base64 alphabet, padded and canonical).
