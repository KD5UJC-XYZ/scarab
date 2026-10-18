### Fixed (Plutus context collection, #176)

- Match reference context-error constructors, payloads, required-purpose collection and error/request
  ordering. Preserve voting anchors and reject invalid V2/V3 redeemer pointers.
- Encode measured Conway context failures exactly, including typed diagnostic normalization while
  preserving original hashed transaction bytes. Retain24 actual submissions and 145 unchanged-reference
  API cases with reproducible offline tooling and small-stack regressions.
