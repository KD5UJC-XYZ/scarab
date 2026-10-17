### Changed (mainnet follower throughput, #147)
- **Ed25519 witness verification is 9% cheaper, with the same verdicts.** `StrictEd25519` tests that a key or an R is a canonical
  encoding as `y < p` on the bytes instead of compressing the decompressed point (two field inversions a verification); the
  equation, the small-order and `S < L` tests and the mixed-order behaviour are untouched. 134 to 122 us a verification on eight
  captured mainnet stretches, 521 KB to 481 KB allocated. `StrictEd25519DifferentialSuite` compares the verdicts with the previous
  verifier on the edge encodings, 100,000 random ones, the published vectors and mutated signatures.
- **`VerificationCache.warm` verifies a witness once** however often its block is warmed (a second peer's batch, a range fetched
  again), and the `verificationCache` event ends with `warmed=skipped/asked`, the witness checks run ahead of the rules against
  those that found their verdict already there. The investigation found no repeated verification in the follower: 99.9987% of the
  rules' witness lookups found their verdict, and a verification is done once for each witness (about 30 to 80 a block).
  `docs/performance.md` section 18 has the numbers, the measurements of the 8.7 blocks/s window of 2026-10-17 and what is left
  unexplained.
