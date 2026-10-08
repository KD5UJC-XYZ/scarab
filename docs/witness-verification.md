# Experimental strict public-input witness verification

## Scope

Version 0.5.0 adds an experimental JVM candidate for the non-ED25519_COMPAT Ed25519 acceptance profile inspected in Cardano node 11.1.3's pinned sodium source. It is **not a claim of complete Cardano compatibility, transaction validity, consensus equivalence, production readiness, or security audit**. No signing, secret-key, HD-key, key generation, external-peer, node or submission API is introduced.

The arithmetic provider is the actual Maven `com.weavechain:curve25519-elisabeth:0.1.3` binary, SHA256 `256048db6904c00832ab6045c624c69844d7617f719e3cd446691257aab8ffcb`. Its matching sources artifact is SHA256 `6259b983a6368c4a39eb39f3db1e78193ba9b82da5d7ec6ea42ccc5514328dda`. These sources differ materially from the earlier roadmap git-head inspection. No reproducible build or independent audit is established. The full MIT/CC0/BSD attribution file is retained, and the runtime audit enforces the inspected binary hash. No threshold-signature, Ristretto, whole weave-java-api or native crypto runtime is imported.

## Public API and error boundaries

All APIs are in `lab.witness` in the pure `core` module; there are no IO or effect-runner calls.

- `PublicKey32`, `Signature64`, `BodyHash32` have exact-size `create(Bytes)` and `fromArray(Array[Byte])` constructors. They are final non-case classes without public constructors or copy methods. Existing immutable `Bytes` owns a Vector; inbound/outbound arrays are copied. Equality and hashCode use byte contents.
- `StrictEd25519.verifyEd25519(publicKey, signature, message)` accepts arbitrary immutable message bytes and returns `Either[VerificationError, VerificationResult]`.
- `StrictEd25519.verify(rawPublicKeyBytes, rawSignatureBytes, message)` also validates container lengths, returning `Either[WitnessInputError | VerificationError, VerificationResult]`.
- Correctly sized noncanonical/off-curve/small-order encodings, noncanonical S, altered messages and failed equations return `SignatureRejected`. They are not malformed container-length errors.
- Bad lengths or CBOR shapes return typed `WitnessInputError`; unsupported parser contexts return `UnsupportedShape`. Unexpected nonfatal provider/programming failures return `VerificationError.ImplementationFailure`, never `SignatureRejected` or success. Fatal JVM failures propagate fail-closed. The witness adapters also catch nonfatal failures while computing the exact-body hash or accessing typed values. Standalone `ExactBodyCbor.hash` can propagate provider failure; it never maps that failure to signature rejection.
- `CardanoWitness.verifyVKeyWitness(BodyHash32, VKeyWitness)` verifies plain Ed25519 on the 32 raw hash bytes. This is not Ed25519ph, body CBOR, CBOR-encoded hash bytes, or a prefixed message.
- The `ExactBodyCbor` overload hashes the original accepted structural map bytes with existing BLAKE2b-256. It never normalizes or re-encodes the body.

The digest is fresh per call. Provider point constructors receive fresh owned arrays: the underlying CompressedEdwardsY retains/exposes mutable arrays and is never exposed through this API. No constant-time claim is made for this new public-input composition.

## Exact verification equation

1. Decode A and R as Edwards points, and require their canonical recompression to match the original input bytes exactly.
2. Reject small-order A or R with the provider's `[8]P == identity` predicate. Do not require prime-subgroup membership: a known accepted mixed-order case would be over-rejected.
3. Require canonical little-endian S strictly below group order L.
4. Compute SHA-512 over original `R || A || message`, then reduce the 64-byte little-endian challenge modulo L.
5. Compare original R bytes exactly to canonical encoding of `[S]B - [h]A`. **No cofactor is applied to this equation.**

Invalid-point and invalid-scalar exceptions are caught only at their known constructors; a broad IllegalArgumentException catch does not surround the arithmetic. The source sodium implementation does not separately decode R, but exact comparison with canonical Rcheck plus its small-order blacklist corresponds to the candidate's stricter explicit decode/round-trip checks under the inspected source/math reasoning. The corpus is supporting evidence, not an exhaustive proof over all encodings.

Direct BC 1.85.2 performs a cofactored check and accepts published speccheck cases 2, 4 and 5 where system sodium rejects. The candidate rejects those and accepts case 3's mixed-order points. This is a target-specific acceptance difference, not a blanket BC vulnerability. BC full-subgroup key validation is not a compatibility fix.

## Container scope

`CardanoWitness.decodeEnvelope` is a deliberately narrow research parser. It requires a four-field envelope, a structural body map with unique unsigned integer keys, Boolean validity flag, null auxiliary data and a nonempty vkey-only witness map with key 0. The vkey set is an array optionally tagged 258; every witness is a two-element key/signature array. Duplicate semantic body/witness-map keys and repeated public keys are rejected. Other witness kinds, script-bearing envelopes and auxiliary data are unsupported, not alleged invalid Cardano transactions. Empty sets are unsupported rather than vacuously verified. Parse the whole supported container before invoking cryptography.

`ExactBodyCbor` validates only the top-level structural body map and its unsigned unique keys. It accepts alternate integer widths and map insertion order within the existing CBOR decoder scope. Those encodings change the exact-byte hash even if they represent equivalent values. It does not establish field correctness, recursive Cardano semantic-key policy, historical ledger acceptance or transaction validity.

Some genuine source transactions include native scripts (witness key 1). The **immutable-pinned fixture harness only** projects their key-0 public signatures and original body bytes, ignoring the unrelated witness fields for that particular signature check. It never calls them supported by the narrow public envelope decoder, evaluates their scripts, or equates valid signatures with ledger acceptance. Generic input cannot reach that projection because admission requires the complete fixture file's immutable hash.

## Evidence and reproduction

See [full provenance, source/oracle status, offsets and licenses](witness-provenance.md). The 2,219 public-only rows include 1,024 upstream sodium known answers, 1,024 S+L negatives, 12 speccheck cases, 151 Wycheproof cases (12 malformed signature lengths), and eight original-byte ledger witnesses. Three witnesses come from accepted setup transactions; five from rejected transactions are additional signature observations. A rejected transaction can have valid signatures.

```sh
./scripts/sbtw scalafmtAll check 'app/run witness-demo' app/runtimeClasspathFile
python3 scripts/project-witness-fixtures.py --check
python3 -O scripts/project-witness-fixtures.py --check
python3 scripts/verify-witness-cli.py
python3 scripts/runtime-inventory.py
python3 scripts/dependency-inventory.py
```

The CLI hashes all three data files against hardcoded expected values before parsing or cryptography. Reads use limit+1 bounded streams (8 MiB per file); editing an adjacent manifest cannot authorize alternate bytes. It reports 2,219 vector checks separately from eight full-source fixture witness re-extractions. Those eight are already included in the 2,219 corpus, not additional independent cases. Mismatch is exit 1; malformed/admission/internal harness failure is exit 2. sbt can normalize application errors to build failure.

System sodium archival comparison used preinstalled libsodium 1.0.18, SHA256 `85e2e494494f4f50c4b6d2b4ae90e885da1bfdefd52c4cc5767fd10abff62505`. It is **not established as the exact Cardano fork/release build**. The native comparison is research-only; the default CLI and tests neither load it nor require it. A subsequent separately built oracle from exact Cardano sodium source commit `dbb48cce5429cb6585c9034f002568964f1ce567` now agrees on all 2,207 fixed-size rows (including eight ledger witnesses); its 12 malformed rows were excluded before native calls. Build evidence establishes the self-built verifier has ED25519_COMPAT disabled. Its source archive SHA256 is `e4f29ae3c16037e484bb69e3fa22a5565c42adf497f8f88e61ff8d9486ab863e`; self-built library SHA256 is `76b41125c867878a1b0cfe64dde580872f61ca4cc6fae06223e902e3e803b24f`. The integrated candidate matches the same pinned expected outputs, including all three BC differences and accepted mixed-order case 3. This strengthens source-profile differential evidence; it does not establish official node distribution compiler flags or binary equivalence. No native binary is bundled or used by the application. See the separate archived source-oracle evidence in [provenance](witness-provenance.md). Wider input-space review, official-release build linkage and an actual security audit remain gates before stronger claims.

Missing full-ledger responsibilities include required signer presence, key-hash/address authorization, scripts, bootstrap witnesses, certificate authority, witness set rules, protocol/era acceptance and all other transaction validity conditions. SignatureVerified proves only this experimental predicate on these supplied public bytes.
