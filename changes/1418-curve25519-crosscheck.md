### Added (crypto, #1418)

- Tests that check the vendored curve25519 arithmetic against independent implementations, before it is hardened
  and rewritten. `FieldElementSuite`, `ScalarSuite` and `EdwardsPointSuite` compare `FieldElement`, `Scalar` and
  `EdwardsPoint` with exact arithmetic on `BigInt` and an independent model of RFC 8032, on inputs weighted toward
  0, the moduli, the limb boundaries and unreduced limbs, and check each operation's documented limb bounds.
  `Ed25519CrossCheckSuite` compares `StrictEd25519` with the JDK's Ed25519 and Bouncy Castle's on valid, mutated and
  edge signatures (S >= L, non-canonical, small-order and mixed-order R and A, x = 0 with the sign bit set, points
  off the curve), stating for each family where the JDK and Bouncy Castle differ from the reference's rule.
- The current Wycheproof Ed25519 vectors (210 cases, `fixtures/wycheproof-ed25519`), run whole against the verdicts
  of the pinned libsodium fork, which decide the 29 cases Wycheproof leaves acceptable; and the reference's seven
  ECVRF draft-03 vectors from cardano-base (`fixtures/vrf-cardano-base`), checked as cardano-base checks them.

#### Known gaps

- `FieldElement.toByteArray` does not reduce every input its documentation admits (limbs below 2^27): `19 * h9`
  overflows 32 bits when limb 9 is above 112142443, and limbs worth 2^257 - 76 are written as the non-canonical
  encoding of p. No caller passes limbs that wide; a test pins the case.
