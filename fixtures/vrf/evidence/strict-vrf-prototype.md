> Public export note: historical run logs and raw diagnostic output are retained only in the private research archive. Their mentions below document prior evidence; they are not bundled public artifacts or newly executed public checks.

# Strict draft03 public-verification prototype

Research date: 2026-10-08 UTC. Isolated experiment in `strict-vrf-probe/`; no main-repository edits or runtime integration. Public verification and public group arithmetic only. No signing, proving, key generation, private keys, listener, security-setting changes, or global installs.

## Result and limits

A small Scala 3 facade plus two public Java math helpers now reproduces the pinned Cardano draft03 rules on the executed corpus. **This is experimental compatibility evidence, not a production verifier, cryptographic audit, or proof of equivalence.** The two previously demonstrated blockers are addressed in source and tested separately: response scalar canonicality and scalar-negation multiplication on mixed-order points. Further decoder/transcript details are addressed too.

Executed final results are in `strict-vrf-probe/summary.json`:

- Existing 2,048-row public corpus: 3 valid with exact expected 64-byte outputs, 2,018 cryptographically rejected, 27 malformed envelopes. Zero mismatches against the pinned native draft03 verifier.
- 3,426 helper comparisons: 612 decode/small-order cases, 519 Elligator map inputs, 240 mixed/torsion/prime-order double-scalar equations, and 2,055 full intermediate traces. Zero mismatches.
- 2,338 additional direct calls: 2,021 fresh native rechecks of every fixed-envelope original row, 34 mixed/torsion/negative-zero public mutations, 256 deterministic unstructured public inputs, and 21 explicit scalar boundaries. Zero mismatches. The original 2,021 are intentionally repeated; these are **rows, not distinct proofs**.
- API checks verify no input mutation, an immutable owned output, independent returned arrays, independence from later caller mutation, malformed-null classification, and an injected helper failure returning `InternalFailure` rather than rejection.

The helper cases do not constitute authentic accepted mixed-order VRF proofs. None were generated. Published valid mixed-order proof coverage remains **unsupported/unestablished**. No mainnet-header fixture or Cardano protocol-alpha integration was added. Protocol claimed-output comparison remains the caller's responsibility.

## Exact implementation

`strict-vrf-probe/src/StrictDraft03.scala` is the public facade. `Ed25519Point.java` is a narrow MIT CCL source extraction/adaptation; `Draft03Math.java` adapts the public-only VrfUtil formulas and implements the exact pinned profile. Runtime dependencies are Scala 3/2.13 libraries and BouncyCastle's public `X25519Field` API. No whole CCL module, Lombok, i2p provider, Weavechain dependency, or native runtime library is used by this prototype. Native artifacts belong only to the differential test harness.

The verification path:

1. Require nonnull 32-byte key, 80-byte proof, and nonnull alpha; empty alpha is allowed. Snapshot input arrays.
2. Decode public key with canonical y (`y < 2^255−19`), on-curve recovery, and reject small-order public keys by `[8]Y == identity`. Do not demand prime-subgroup membership.
3. Decode Gamma with canonical y/on-curve checks, without a small-order or subgroup rejection.
4. Parse c from 16 little-endian bytes, s from 32. Require `s < L`, including boundary `L` rejection.
5. Hash `04 || 01 || encode(Y) || alpha` with SHA-512; take 32 bytes and clear bit 255; apply old Elligator2 and cofactor multiplication.
6. Compute `cn = (−c) mod L`, then **U = [cn]Y + [s]B**, **V = [cn]Gamma + [s]H**. It deliberately does not use point subtraction `[s]B − [c]Y` on arbitrary accepted points.
7. Compare c with the first 16 bytes of SHA512(`04 || 02 || H || original-Gamma-bytes || U || V`). No Y term and no trailing zero.
8. Only after a challenge match, return SHA512(`04 || 03 || encode([8]Gamma)`) as an owned immutable `Vector[Byte]`. No trailing zero.

### Additional corrections uncovered by source review

Native `ge25519_frombytes` allows x=0 with sign bit 1. CCL's original decoder explicitly rejects it. The adapted decoder permits it, while native-compatible public-key small-order rejection still excludes such public keys. Gamma is different: it may reach the challenge stage. Native hashes the **original 32 Gamma bytes**, whereas CCL's original `hashPoints` hashes a normalized re-encoding. The adaptation preserves original Gamma bytes and direct negative-zero trace tests cover this distinction. This is why a scalar-only patch would remain inadequately justified.

CCL's general Elligator expression used the Legendre value directly and Java `modInverse`; the adaptation follows native conditional nonsquare selection and zero-inversion/exceptional Montgomery conversion behavior. Both sign choices and boundary uniform strings are checked against native `ge25519_from_uniform`. In the actual VRF path the sign is cleared before mapping. SHA-512 is streamed over pieces, avoiding a concatenation-size overflow.

## Native source review and oracle construction

Source revision: `IntersectMBO/libsodium@dbb48cce5429cb6585c9034f002568964f1ce567`.

The complete `crypto_vrf/ietfdraft03/verify.c` verification/proof-to-hash path was read. Reviewed helpers include `ge25519_frombytes`, `ge25519_is_canonical`, the full seven-entry `ge25519_has_small_order` blacklist, `sc25519_is_canonical`, `crypto_core_ed25519_scalar_negate`, scalar recoding, fixed- and variable-base double-scalar multiplication and precomputation, cofactor clearing, old Elligator2, Montgomery-to-Edwards conversion, and from-uniform. Scalar negation reduces modulo L; ordinary point multiplication is then on the full decoded group. No generic `crypto_vrf_verify` call appears in the harness: that unversioned alias targets draft13 and is not this profile.

The main oracle is the previously built pinned-source shared library, SHA-256:

`76b41125c867878a1b0cfe64dde580872f61ca4cc6fae06223e902e3e803b24f`

`helper.c` builds a **test-only bridge** linked to that build's existing unmodified `ed25519_ref10.o`, with `HAVE_TI_MODE=1` matching the original configure result, and to that exact shared library. It invokes actual native decoder/map/double-scalar helpers. Full trace comparison exposes native H/U/V/challenge/output stages, with the fixed-base native U function and variable-base native V function used by production source. The trace's SHA transcript assembly mirrors the reviewed verify source; direct native verify comparisons independently cover the final acceptance decision. The bridge is instrumentation, not an official sodium API or independently shipped Cardano binary. Sodium emits its expected “undocumented method” warning for this direct internal-header bridge; it is retained in `run.log` and is not suppressed.

All 8 public torsion points are obtained by repeated addition of the published order-8 point `26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05`. Adding each to the published Edwards basepoint produces prime/mixed-order controls. The 240 equation checks cover these 16 points, c ∈ {0,1,2,17,2^128−1}, and s ∈ {0,1,L−1}. These are publicly specified integers and public group elements, with no secret/proving operation. Both all-torsion and mixed points matter, because Gamma has no small-order rejection.

The decoder corpus includes near-zero/near-p y encodings with both signs, 512 SHA256-derived arbitrary byte strings, and the torsion/mixed points. Uniform mapping includes seven boundaries and 512 SHA256-derived public byte strings. These are deterministic finite probes, not exhaustive/randomized cryptographic testing.

## API and errors

The facade returns one of:

- `Verified(Vector[Byte])`: the owned 64-byte output after successful proof verification.
- `MalformedEnvelope(reason)`: null/incorrect-size inputs; never forwarded to the native ABI.
- `Rejected(reason)`: recognized input/policy/challenge rejection.
- `InternalFailure(kind, detail)`: nonfatal unexpected math/provider failure.

Only nonfatal exceptions become `InternalFailure`; fatal JVM errors are not silently converted to invalid proofs. A package-scoped injectable map seam is used solely to test this classification. There is no public unchecked proof-to-hash API. Public variable-time math is unsuitable for secret scalars. Input snapshots protect against later mutation, but callers must not concurrently mutate arrays while the snapshots are being taken; no atomic snapshot guarantee is claimed. Alpha is currently unbounded by this primitive facade, so application resource limits are still required. Detailed errors are research diagnostics, not a settled network-facing error contract.

## Provenance, licensing, and reproduction

CCL source revision: `bloxbean/cardano-client-lib@45906b783973b4c44ac6883253af26bdd07bc1b4`, public `crypto-ext/.../vrf/bc/Ed25519Point.java` and `VrfUtil.java`. Source adaptation attribution is retained, and the complete CCL MIT notice is in `CCL-LICENSE`. Sodium ISC notice is in `sodium-LICENSE`; BouncyCastle notice is retained in `BC-LICENSE`. Dependency licenses remain separately applicable; this is not a completed redistribution review.

Compiler: existing OpenJDK 21.0.12.1, Scala 3.3.8 with Scala library 2.13.18. Public field provider: `bcprov-jdk18on:1.83`, reused exact jar from the preceding probe. Native bridge: existing GCC 14.2.0. Scala compilation uses explicit cached compiler dependencies; runtime uses only Scala libraries, BC, and compiled prototype classes. No global install or download was needed.

From the workspace root:

`bash cardano-research/strict-vrf-probe/run.sh`

The runner first checks `pinned-inputs.json`, compiles only the isolated sources, executes original fixtures, builds the native helper bridge, regenerates deterministic helper/direct-oracle results, runs the Scala harness and API checks, and asserts zero mismatches. `check.py` checks row-by-row results, not just acceptance counts. `integrity.py` refuses changed pinned inputs and optimized Python execution. `sha256.json` records final probe artifacts, including the bridge source/binary and all retained results. The exact source-built oracle provenance and original three published IETF draft03 fixture origins are detailed in `pinned-crypto-oracle.md` and `next-vrf-slice.md`.

## Remaining gates

Before any main-repo acceptance or production claim: obtain authentic Cardano/header positives and accepted mixed-order proof fixtures; verify protocol message and claimed-output construction; conduct independent arithmetic/decoder/transcript review, broader adversarial testing, JVM/provider failure and bounded-resource analysis, license/dependency review, and shipped-binary provenance checks. The existing Ed25519 witness primitive remains separate. The available evidence supports continuing an isolated implementation experiment, not silently replacing a consensus verifier.
