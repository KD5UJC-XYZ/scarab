### Changed (crypto, #1418)

- The vendored curve25519 decoders return a result instead of throwing: `CompressedEdwardsY.decompress` and
  `CompressedRistretto.decompress` give an empty `Optional` for bytes that encode no point, and
  `Scalar.fromCanonicalBytes` for bytes that are not the 32-byte encoding of a scalar below the group order, of any
  length. `InvalidEncodingException` and the classes' `java.io.Serializable` hooks, which nothing serialized, are
  gone. The same inputs are accepted and refused as before.
- VRF proving refuses a hash-to-curve point that does not decode with its own defect case,
  `Draft03Prover.Failure.HashToCurveEncoding`, which stops the node as other internal failures of the forge do,
  instead of an `InternalFailure` caught from the decoder's exception. No input reaches it.

### Added (crypto, #1418)

- `EntryPointTotalitySuite`: every entry point of `crypto` that reaches its Java sources (Ed25519, VRF, KES and
  operational certificate verification, VRF proving, KES signing, evolution and key loading) returns a value for
  bytes of every length and content, and for inputs one bit from accepted ones.
- `scripts/check_java_sources.py`, a guard: the Java main sources are those that `docs/data/java-sources.json`
  lists, and the `throw` statements in them, counted against `docs/data/java-throw-baseline.json`, only go down.
