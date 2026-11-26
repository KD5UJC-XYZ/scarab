### Changed (crypto, #1418)

- The vendored curve25519 decoders return a result instead of throwing: `CompressedEdwardsY.decompress` gives an
  empty `Optional` for bytes that encode no point, and `Scalar.fromCanonicalBytes` for bytes that are not the 32-byte
  encoding of a scalar below the group order, of any length. `InvalidEncodingException` and the classes'
  `java.io.Serializable` hooks, which nothing serialized, are gone. The same inputs are accepted and refused as
  before.
- VRF proving refuses a hash-to-curve point that does not decode with its own defect case,
  `Draft03Prover.Failure.HashToCurveEncoding`, which stops the node as other internal failures of the forge do,
  instead of an `InternalFailure` caught from the decoder's exception. No input reaches it.
- A JDK without SHA-512 no longer leaves KES signing, evolution and key loading to throw: the KES and VRF signing
  keys find the JDK's SHA-512 digest once, where they are made or read, and refuse with
  `Sum6KesSigning.Failure.Sha512Unavailable` or `Draft03Prover.Failure.InternalFailure`, which stop the node as
  defects. Every hash of the keys' secrets after that works on a copy of that digest, with no lookup, so it cannot
  fail for a digest whose copy depends on its class only, as the JDK's does; with a provider whose copy could fail
  later, the node stops on it as a defect. The VRF prover's hash to curve, of public inputs, still asks the JDK for
  SHA-512 on each proof, and its refusal is an `InternalFailure`. A start whose reading of the key files throws
  fails with `ForgeError.KeyFilesDefect`, logged as a refusal of the files is, instead of a stack trace.
- The recovery of a KES key replacement that a crash interrupted destroys or replaces a file for being bad only
  when its content is refused (bytes that are not UTF-8 among them); a valid stage still replaces an older valid
  key, and a stage of another key or an earlier period is still destroyed. A file that cannot be read, or whose
  read meets a defect such as a JDK without SHA-512, leaves both files as they are, and the start fails with that
  error; before, the stage was destroyed. A key the recovery reads and does not keep is erased however it ends,
  and a key file back by the time the replacement reads it again is not taken for a replaced one.

### Removed (crypto, #1418)

- The ristretto255 group of the vendored curve25519 source (`CompressedRistretto`, `RistrettoElement`,
  `RistrettoGeneratorTable` and the constants only they read), which nothing used.

### Added (crypto, #1418)

- `EntryPointTotalitySuite`: every entry point of `crypto` that reaches its Java sources (Ed25519, VRF, KES and
  operational certificate verification, VRF proving, KES signing, evolution and key loading) returns a value for
  bytes of every length and content, and for inputs one bit from accepted ones.
- `scripts/check_java_sources.py`, a guard: the Java main sources are those that `docs/data/java-sources.json`
  lists, none holds a Unicode escape or a character javac ignores inside a token, and the count of `throw` in
  each, against `docs/data/java-throw-baseline.json`, cannot rise unless the change rewrites the baseline as
  well.
