### Fixed (Plutus)

- Require a script's flat program to end exactly behind its filler, as the reference's strict decoder does; bits or bytes after it make the script malformed. Bytes after the script's CBOR byte string remain a V1/V2 remainder. Reference verdicts: `fixtures/reference/plutus-flat-decode`.
