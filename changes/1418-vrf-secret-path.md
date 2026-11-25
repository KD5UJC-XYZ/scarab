### Fixed (VRF proving's secret path, #1418)

- The VRF output of a slot no longer decodes Gamma, a point computed from the pool's VRF key, with
  the variable-time decoder, and a proof no longer decodes U and V with it: the output is taken from
  the constant-time point and the challenge from the encodings as computed. A forgotten key is known
  by a flag rather than by reading its nonce key up to its first non-zero byte. Proofs and outputs are
  unchanged, byte for byte.
- The VRF signing key keeps only its 32-byte seed, which `forget` zeroes; before, the scalar derived
  from it stayed in memory as an immutable value after `forget`. Each proof or output computes the
  scalar, the nonce and their digits and encodings in working storage that is zeroed when it ends, on
  failure too. A VRF key read by a reload that finds no credentials, or by a start-up whose credentials
  do not load, is zeroed. The curve arithmetic's own intermediates are not erased, and the JVM may have
  copied an array before it is zeroed.

### Added (VRF secret path guard and timing test, #1418)

- `scripts/check_crypto_guard.py` refuses a variable-time routine (the `vartime*` operations,
  `VartimeEd25519`, the NAF recoding, Bouncy Castle's `*Var` routines, `Ed25519Point`, `Draft03Math`,
  the verifiers, big-integer arithmetic) in the package of the VRF prover's secret steps, and any call
  of the prover into `Draft03Math` but its hash to curve.
- `SecretPathTiming` (`tools`), a dudect-style fixed-against-random timing test of proving, the output
  and the constant-time multiplications, with three controls: random against random, straight-line field
  arithmetic, and a variable-time multiplication. On the machine measured it finds nothing in the secret
  path that it does not also find in straight-line arithmetic (performance section 67, which says what such
  a test cannot show).
