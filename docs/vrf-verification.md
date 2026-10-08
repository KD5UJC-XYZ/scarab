# Experimental public-only draft03 VRF verification (0.7.0)

The primitive scope below is unchanged. Version 0.9.0 adds a separate [Praos alpha/certificate layer](praos-certificates.md), with four archived public preprod certificate positives and explicitly supplied epoch nonces; historical chain inclusion remains unauthenticated. Statements below about missing protocol integration and Cardano-header positives describe the original 0.7.0 milestone.

This milestone integrates a **research verifier, not a production or consensus verifier**. It accepts public key, proof and already-constructed alpha bytes and returns output only after the proof's challenge verifies. It does not sign, prove, generate keys, expose unchecked proof-to-hash, construct Cardano protocol alpha, compare a separately claimed protocol output, contact nodes, or validate headers/transactions. No private-key fixtures or native runtime artifacts are included.

## API and resource contract

`lab.vrf.StrictDraft03.verify(publicKey: Bytes, proof: Bytes, alpha: Bytes): Result` accepts the existing immutable `lab.cbor.Bytes`. Array entry/exit uses owned copies. Results are `Verified(Bytes)` (64 bytes), `MalformedEnvelope(reason)`, `Rejected(reason)`, or `InternalFailure(kind, detail)`. The categories are typed; explanatory strings are research diagnostics, not a stable network error contract. Unexpected nonfatal failures stay internal failures; fatal JVM errors propagate. A package-only map seam tests this behavior. The point and math helper Java classes are package-private, so unchecked output computation is not a public API.

Keys must be exactly 32 bytes; proofs exactly 80 bytes; alpha may be empty and is bounded to 1 MiB by this research API. Null key/proof/alpha is malformed. A deliberately malformed `Bytes(null)` object is programming misuse and becomes `InternalFailure`; it is not an adversarial byte sequence. The limit is a local resource policy, not a Cardano consensus rule. Public arithmetic is variable-time and must never be repurposed for secrets. Concurrent immutable callers do not share digest state. This work is not a full adversarial resource/side-channel audit.

`app/run vrf-demo` loads only the two pinned original-corpus files, each through an 8 MiB bounded read; immutable SHA-256 admission precedes parsing and verification. No arbitrary filename or unpinned input mode is offered. Input/admission/internal errors return exit 2, conformance mismatch returns 1, exact match returns 0 (sbt may collapse nonzero statuses to 1). The other existing commands remain available.

## Exact source profile

This is Cardano sodium **`crypto_vrf_ietfdraft03_verify`**, not the unversioned alias (draft13), RFC 9381 or the draft13 batch-compatible profile.

1. Decode Y with canonical y below p and on-curve recovery; reject small-order Y by `[8]Y == identity`. No prime-subgroup requirement is imposed.
2. Decode Gamma with canonical y/on-curve checks, without a small-order/subgroup rejection. The native x=0/sign=1 encoding is allowed.
3. Parse 16-byte little-endian c and 32-byte little-endian s; require **s < L**.
4. H is old Elligator2/cofactor mapping of the first 32 bytes of SHA512(`04 || 01 || encode(Y) || alpha`), clearing bit 255. Exceptional mapping/zero inversion follows the pinned native source.
5. Compute `cn = (-c) mod L`, `U = [cn]Y + [s]B`, `V = [cn]Gamma + [s]H`. Ordinary point subtraction is not interchangeable on mixed-order inputs.
6. Compare c with the first 16 bytes of SHA512(`04 || 02 || H || original-Gamma-bytes || U || V`). There is no Y term or terminal zero. Preserve original Gamma bytes, including negative-zero, rather than normalizing the transcript.
7. Only after that match, return SHA512(`04 || 03 || encode([8]Gamma)`). No terminal zero.

The earlier CCL code's missing canonical-s guard demonstrably over-accepted 45 scalar mutations. The scalar-negation and negative-zero/raw-Gamma corrections are separately material; this is not merely that guard patched onto an otherwise assumed-compatible verifier.

## Evidence and reproduction

From the repository root, with the ordinary JDK/build requirements:

```sh
python3 scripts/verify-vrf-corpus.py
python3 scripts/verify-vrf-corpus-negative.py
./scripts/sbtw check app/runtimeClasspathFile 'app/run vrf-demo'
python3 scripts/runtime-inventory.py
python3 scripts/verify-runtime-inventory.py
python3 scripts/verify-vrf-cli.py
```

The portable Python checker uses only the standard library and vendored files. It validates all file hashes, exact row IDs/counts, and deterministic input/expected projections from retained native-oracle JSON. It performs **no fresh native execution**. Scala tests replay those archived expectations against the actual resolved BC 1.85.2 runtime:

- 2,048 original rows: 3 published positive outputs, 2,018 rejected, 27 malformed envelopes excluded from native invocation.
- 2,338 additional direct-oracle rows: **2,021 intentionally repeated original fixed-envelope rechecks**, 34 mixed/torsion/negative-zero mutations, 256 deterministic unstructured public inputs, 21 scalar boundaries.
- 3,426 instrumentation comparisons: 612 decode/small-order cases, 519 map inputs, 240 mixed/torsion/prime-order equations, 2,055 intermediate traces.

These 7,812 retained rows are not 7,812 independent proofs. Only three distinct published authentic generic VRF positive fixtures underpin this corpus. The helper bridge uses native internal routines and manually mirrored transcripts; it is **test instrumentation, not an official public sodium API**. Direct native verify results independently cover acceptance. No authentic accepted mixed-order VRF proofs were constructed or obtained. No authentic Cardano/mainnet-header positive fixture or protocol-level integration is established. A finite corpus, even zero mismatches, cannot establish universal equivalence or cryptographic correctness.

A separate Python affine-coordinate implementation adds 104 public checks: 64 deterministic point-pair/scalar equations, 38 noncanonical y encodings, and two negative-zero encodings. The generator and expected results are retained; these are independent arithmetic controls, not accepted VRF proofs.

Own tests additionally cover immutable ownership, exact alpha boundary and overflow rejection, canonical scalar boundary, raw negative-zero Gamma transcript, the mixed-order equation distinction, unexpected/fatal failures, parallel verification, and pinned CLI ingress failures. Native-free archive scanning verifies the resolved application classpath, not arbitrary future inputs or a complete security audit.

## Sources, dependencies and licenses

- Cardano node source: [11.1.3 pin](https://github.com/IntersectMBO/cardano-node/tree/938cba990357ae7c4b7f95c8f75dd9d31174bbeb); exact release cardano-base `060819b59c184b951a54e3c563304983c53a3eac` binds `PraosVRF` to draft03.
- [Cardano sodium source](https://github.com/IntersectMBO/libsodium/tree/dbb48cce5429cb6585c9034f002568964f1ce567): pinned-source self-built oracle, source archive SHA-256 `e4f29ae3c16037e484bb69e3fa22a5565c42adf497f8f88e61ff8d9486ab863e`, shared library SHA-256 `76b41125c867878a1b0cfe64dde580872f61ca4cc6fae06223e902e3e803b24f`. GCC 14.2.0; `-O2 -g0 -UED25519_COMPAT`; internal helper build used `HAVE_TI_MODE=1`. This is not a proven equivalent of an official shipped node binary. Neither native binary is bundled or loaded here.
- The public positives originate in [pinned sodium vrf_03.exp](https://github.com/IntersectMBO/libsodium/blob/dbb48cce5429cb6585c9034f002568964f1ce567/test/default/vrf_03.exp), whose paired source identifies published IETF draft03 Appendix A.4 examples. Only public keys/proofs/outputs and empty/`72`/`af82` messages were extracted; the secret-generating upstream executable was not run.
- Narrow [Bloxbean CCL source adaptation](https://github.com/bloxbean/cardano-client-lib/tree/45906b783973b4c44ac6883253af26bdd07bc1b4/crypto-ext/src/main/java/com/bloxbean/cardano/client/crypto/vrf/bc): `Ed25519Point.java` and public formulas from `VrfUtil.java`. Changes include package/private surface, negative-zero decode, exact Elligator edge semantics, modulo-L scalar equation, and original-Gamma transcript; the Scala facade adds canonical-s, ownership, typed failures and size bounds. **MIT**, full BloxBean 2024 copyright and permission notice retained in `fixtures/vrf/licenses/CCL-LICENSE`.
- Native-derived evidence retains sodium ISC notice, and BC's permissive notice is also retained under `fixtures/vrf/licenses`. Original facade/test/application code is Apache-2.0. Dependency licenses remain separately applicable; this is not a legal audit.
- Runtime uses the existing **BC 1.85.2**, public `X25519Field`, JDK BigInteger/SHA-512 and Scala; no whole CCL dependency, i2p provider, Lombok, new dependency, JNI/JNA or native runtime. BC jar SHA-256 `986b0fb92ec10e0c66b43e036ce0077e6150cfaecd1db9fb92b56672e157afe5` is checked by the runtime audit. Prototype used BC 1.83; compatibility is rechecked, not inferred. Public BC field methods emit a javac deprecation notice; future upgrades need another review and corpus run.

`fixtures/vrf/evidence` retains original research reports, source/result manifests, native bridge source, historical generator and public input/native-result JSON. Historical scripts/reports refer to the original external research workspace and are archival context, not the portable reproduction entrypoint. The portable verifier above needs no such workspace. `fixtures/vrf/sha256.json` pins the retained evidence and projections; application ingress additionally embeds immutable pins. Manifests detect changes, not malicious coordinated replacement or proof of oracle correctness.

## Remaining gates

Independent cryptographic/arithmetic review, broader adversarial corpora, authentic Cardano-header positives, accepted mixed-order proofs, protocol alpha and claimed-output integration, bounded-resource assessment, dependency/license review and official binary provenance remain open. Do not substitute this research primitive into a consensus implementation on the strength of these tests. VRF does not replace the separate Ed25519 witness predicate or KES validation.
