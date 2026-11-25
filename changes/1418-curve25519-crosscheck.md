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

### Fixed (crypto, #1418)

- `FieldElement.toByteArray`, the reduction of a field element to its canonical encoding, was wrong for some inputs
  inside its documented range (limbs below 2^27 in absolute value) but outside what any caller produces: an
  intermediate product overflowed 32 bits, and a multiple of p could be written as the non-canonical encoding of p.
  It is now exact over the whole documented range. No point encoding and no accepted or rejected signature
  changes.
- `Scalar.add` and `Scalar.subtract` failed for some unreduced operands, which their documentation admits, instead
  of returning a + b and a - b modulo L. They now reduce both operands first and always return a canonical scalar.
  The node does not use them; no verdict changes.
