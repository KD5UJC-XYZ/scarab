### Changed (Plutus: Scalus 1.4.0 and scalus-crypto-jni)

- Plutus evaluation uses Scalus 1.4.0. Its cryptographic builtins (Ed25519, secp256k1,
  BLS12-381) run on `libscalus_crypto` of `org.scalus:scalus-crypto-jni:0.1.0`, which replaces
  blst-java and scalus-secp256k1-jni on the Plutus path ([ADR 0020](docs/adr/0020-plutus-native-crypto-through-scalus-crypto-jni.md)).
- `follow` checks that library at start-up and refuses to start with `NativeLibraryError` if it is
  not the pinned build of the platform, is shadowed on `java.library.path`, does not load, or
  fails a known answer. A `plutusCrypto` event reports it. Linux needs glibc 2.33 or newer
  (musl is unsupported) and a `java.io.tmpdir` that exists and allows execution.
- Bouncy Castle is 1.86, the version Scalus 1.4.0 brings.

### Removed (Plutus wrapper corrections fixed upstream)

- The variant-A `verifyEd25519Signature` costing correction, the `verifyEcdsaSecp256k1Signature`
  replacement, the raw-DST `hashToGroup` adapter with its ASCII-only refusal, and the
  `BinaryDstUnavailable` and `NativeHashToGroupUnavailable` failures: Scalus 1.4.0 matches the
  reference there. The `RawDst` JNI class of `blst-native` is removed too.
