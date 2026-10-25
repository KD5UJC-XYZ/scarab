### Fixed (security)
- **`serialiseData` writes a wide constructor index as the reference does.** A tag-102 constructor index outside 64 bits is
  now one definite byte string under tag 2 or 3, however long (cborg `encodeInteger`); only `Data.I` is chunked in 64-byte
  pieces. Expected bytes and digests at 65, 72, 512, 513, 600 and 1024 bits come from the executed plutus 1.70.0.0 reference
  (`fixtures/reference/plutus-serialise-data`).
- **`constrData` takes its index as the reference does.** At protocols 9 and 10 a negative index now builds constructor
  data that `serialiseData`, `unConstrData`, `equalsData` and `chooseData` read as the reference does, with the reference's
  budget; it no longer ends the evaluation as an unsupported outcome. From protocol 11 an index outside `[0, 2^64)` fails
  the script, as the reference's Word64 unlifting does. Verdicts and budgets come from the same reference
  (`constr-index-verdicts.jsonl`).
