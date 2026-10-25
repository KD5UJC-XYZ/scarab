### Fixed (Plutus)

- Decode flat scripts with Scarab's own stack-safe decoder instead of Scalus's, matching the reference for `data` constants of any depth, bytes after a `data` constant's CBOR item, `string` constants that are not UTF-8 and program version components beyond 32 bits. Reference verdicts: `fixtures/reference/plutus-flat-decode`.
- Match the reference's flat `Word64` (at most ten groups of seven bits), its `data` big numbers (tags 2 and 3 only behind the short head) and its outer script byte string (definite length only).
