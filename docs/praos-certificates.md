# Experimental Praos alpha and certificate predicate (0.9.0)

This pure, public-only research layer builds Praos alpha and checks a VRF certificate. A `VerifiedCertificate` establishes only that the supplied public key/proof verifies for the explicitly supplied slot/epoch nonce and that the independently claimed 64-byte output equals the primitive output. It is **not a valid-header result**, stake/leader eligibility, key registration/binding, KES/opcert verification, epoch nonce evolution, or a consensus/security audit. It does not support TPraos. No native runtime, network access, signing, proving or key generation is added.

## Checked immutable API

`lab.vrf.PraosVrfCertificate` exposes private-constructor `Slot`, `Hash32`, and `Input` values. `Slot.fromBigInt` accepts precisely 0 through 18446744073709551615; negative, overflow and null are malformed. `Hash32.fromBytes` accepts exactly 32 immutable bytes. `NeutralNonce` is a separate value, contributing **zero bytes**, distinct from a 32-byte zero hash. `Input.create` rejects null components. These types have no public case-class copy or unchecked constructor. Arrays enter and leave through owned `Bytes` copies.

`alpha(input)` computes Blake2b-256 over exactly eight unsigned big-endian slot bytes followed by the nonce bytes. No CBOR, text, length prefix, network identifier or era tag is included. It returns `Either[Failure, Bytes]` with malformed/internal failure categories. Every successful alpha is 32 bytes. The supplied epoch nonce is external protocol state, not extractable from an isolated header.

`verify(input, key, proof, claimedOutput)` prevalidates 32/80/64-byte envelopes, builds alpha and calls `StrictDraft03.verify` once. It returns `Malformed`, `ProofRejected`, `OutputMismatch`, `InternalFailure`, or `VerifiedCertificate(output)`. Proof rejection and output mismatch have no output field. It never exposes unchecked proof-to-hash. Unexpected nonfatal errors stay internal; fatal JVM errors propagate. Null public inputs are malformed; deliberately constructing `Bytes(null)` is programming misuse and an internal failure. A package-private seam tests failure behavior, not a public alternate verifier.

Pinned semantics:

- [Ledger Praos VRF](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-protocol/src/Cardano/Protocol/Praos/VRF.hs): `mkInputVRF`, unsigned big-endian slot, neutral nonce, raw hash bytes.
- [Cardano-base certified output](https://github.com/IntersectMBO/cardano-base/blob/060819b59c184b951a54e3c563304983c53a3eac/cardano-crypto-class/src/Cardano/Crypto/VRF/Class.hs): `verifyCertified` verifies then compares output.
- [Consensus Praos](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/ouroboros-consensus-protocol/src/ouroboros-consensus-protocol/Ouroboros/Consensus/Protocol/Praos.hs): additional pool-key/threshold checks remain excluded.

These are Cardano node 11.1.3 source pins. TPraos has additional seed XOR separation and two certificates; similar data is not permission to treat it as Praos. Hard-fork history and era activation are not inferred here.

## Four public archived positives, with explicit context

The [Amaru archive](https://github.com/pragma-org/amaru/tree/34a453005bcaaf837ee73bd996b99eab8ef92961/crates/amaru-consensus/tests/data/headers) labels the four artifacts preprod. Their historical chain inclusion has **not been independently authenticated**. Amaru is a separately pinned archive, not the Cardano release implementation oracle.

- Slots 70070331 and 70070379: source epoch 165, nonce `a7c4477e9fcfd519bf7dcba0d4ffe35a399125534bc8c60fa89ff6b50a060a7a`.
- Slots 70070426 and 70070464: source epoch 166, nonce `b2853ec951e7ed91b674a47c8276189f414e22b19d61d9da0ac7490801e4bf0d`.

Each nonce is bound to its named `PREPROD_NONCES_<slot>` block in [pinned store.rs](https://github.com/pragma-org/amaru/blob/34a453005bcaaf837ee73bd996b99eab8ef92961/crates/amaru-consensus/src/store.rs). All four proofs passed the pinned native draft03 verify-only oracle and matched their separately archived claimed output. The shared library hash was `76b41125c867878a1b0cfe64dde580872f61ca4cc6fae06223e902e3e803b24f`, sodium revision `dbb48cce5429cb6585c9034f002568964f1ce567`; this self-build is not established equivalent to an officially shipped binary. No native library is bundled or invoked by repository tests.

The original single-nonce research JSON is retained unchanged: its final two rows reject because they use epoch 165's nonce on epoch 166 fixtures. They are **wrong-context certificate controls, not invalid-header evidence**. `epoch166-header-results.json` records the subsequent successful context-correct checks. The archived initial research note predates this discovery and is superseded on that point by this document. The initial Amaru full-header test fails for missing stake distribution, not for the narrower VRF certificate check.

## Deterministic offline admission and reproduction

From the repository root:

```sh
python3 scripts/project-praos-fixtures.py
python3 scripts/verify-praos-projector.py
./scripts/sbtw check app/runtimeClasspathFile 'app/run praos-demo'
python3 scripts/verify-praos-cli.py
python3 scripts/runtime-inventory.py
python3 scripts/verify-runtime-inventory.py
```

The standard-library Python projector checks exact retained file hashes, immutable upstream source hashes, fixed header shape/complete consumption, public key/proof/claim extraction, named source nonce and epoch binding, and independent Python Blake2b-256 alpha. Its tiny definite-length CBOR extractor is a **fixture-only boundary**, not a public Scala header decoder or general consensus CBOR admission. It only operates on four pinned 859-byte artifacts. Native results are archived, not regenerated. The repository manifest detects changes; it cannot defend against coordinated malicious replacement of both evidence and pins.

The admitted TSV has 18 certificate rows: 16 native mutation rows around the original two positives, plus two epoch-166 positives. Four rows verify, two valid primitive proofs fail the claim comparison, and twelve reject cryptographically. The mutation rows cover changed slot, little-endian slot construction (represented as its equivalent unsigned big-endian semantic slot), neutral/zero nonce, key/proof bit changes and claimed-output changes. Tests additionally reject both epoch-166 proofs under the previous epoch's nonce. Fifteen independent Python/source-derived controls cross slots 0, 1, 2^63−1, 2^63 and 2^64−1 with neutral/zero/ascending nonces. These are not claimed as reference-Haskell goldens; expected values are never generated by the Scala implementation under test.

Own tests cover constructor ranges, null and wrong envelope lengths, malformed `Bytes`, immutable ownership, primitive call count, no unchecked output path, output mismatch, mapped/unexpected/fatal failures and parallel callers. Negative admission tests cover truncation, trailing data, unsupported forms, length/depth limits, projection/evidence tampering, source epoch binding and extra files. The CLI reads only `fixtures/praos/certificates.tsv` with a 64 KiB bound and embedded SHA-256 pin before parsing; no arbitrary file mode exists. Exit 0 means matching expectations, 1 means conformance mismatch, and 2 means input/admission/internal failure (sbt can collapse errors).

Amaru Apache-2.0, ledger Apache-2.0, consensus Apache-2.0 licenses/notices are retained alongside evidence; draft03 native-derived evidence remains subject to the existing sodium ISC notice in `fixtures/vrf/licenses`. Runtime dependencies are unchanged. Finite fixture agreement and this implementation review do not establish universal cryptographic equivalence or adversarial resource safety. Accepted mixed-order proofs, independent cryptographic review and full consensus integration remain open.
