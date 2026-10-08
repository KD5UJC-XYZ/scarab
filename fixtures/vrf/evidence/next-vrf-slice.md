# Next VRF slice: pinned public-verification differential

Research date: 2026-10-08 UTC. Isolated public-input research only. No main repository edits, real/private keys, key generation, proving, signing, node runtime, network listener, deployment, or external communication.

## Decision

**Do not adopt either unmodified Bloxbean verifier as a Cardano-compatible consensus verifier.** Both validate the three published draft03 examples and produce the exact native 64-byte output, but both accept noncanonical response scalars rejected by the pinned Cardano sodium implementation. The strict Ed25519 witness verifier is a different primitive and is not a substitute for VRF.

The two CCL classes remain useful readable JVM implementation material. Their license at this source pin is **MIT**, not Apache-2.0. A small audited source extraction plus Scala 3 facade could avoid the broad library/prover surface, but this packet deliberately does not implement or approve that path. Scalar canonicality is one demonstrated blocker, not a claim that adding one guard completes compatibility. Mixed-order arithmetic semantics and decoder differences also require targeted review.

## Exact provenance and artifact availability

- Cardano node 11.1.3 source: `938cba990357ae7c4b7f95c8f75dd9d31174bbeb`.
- Exact release cardano-base: `060819b59c184b951a54e3c563304983c53a3eac` (release metadata retained in witness-slice-probe/node-release.json).
- Sodium source: `dbb48cce5429cb6585c9034f002568964f1ce567`; already built under cardano-sodium-oracle. Loaded library SHA-256 `76b41125c867878a1b0cfe64dde580872f61ca4cc6fae06223e902e3e803b24f`. See pinned-crypto-oracle.md for archive integrity, compiler and build flags. This is a pinned-source self-build; official shipped-node binary equivalence is **not** established.
- Bloxbean source: `45906b783973b4c44ac6883253af26bdd07bc1b4`, commit dated 2026-10-05. Checkout `weave-sources/cardano-client-lib`; no source edits. [Source tree](https://github.com/bloxbean/cardano-client-lib/tree/45906b783973b4c44ac6883253af26bdd07bc1b4/crypto-ext/src/main/java/com/bloxbean/cardano/client/crypto/vrf).
- Maven Central actually serves `com.bloxbean.cardano:cardano-client-crypto-ext:0.8.0-pre5-dev1`, binary, POM and sources, not merely an assumed coordinate. [Metadata](https://repo.maven.apache.org/maven2/com/bloxbean/cardano/cardano-client-crypto-ext/maven-metadata.xml), [POM](https://repo.maven.apache.org/maven2/com/bloxbean/cardano/cardano-client-crypto-ext/0.8.0-pre5-dev1/cardano-client-crypto-ext-0.8.0-pre5-dev1.pom). Metadata captured in vrf-slice-probe/maven-metadata.xml also lists pre3, pre4 and pre5. The checkout version is `0.8.0-pre6-SNAPSHOT`; do not infer that this exact snapshot is published.
- All nine inspected public VRF/interface/result/Cardano helper Java files in that published sources.jar are **byte-identical** to this checkout. Recorded per-file hashes: vrf-slice-probe/published-source-comparison.json. No assertion that the entire release equals the later commit.
- Exact-source Java compilation used OpenJDK 21.0.12.1, BouncyCastle `bcprov-jdk18on:1.83`, Bloxbean `net-i2p-crypto-eddsa:0.3.1`, and Lombok 1.18.42. These match pinned Gradle dependency declarations (not the separate witness probe's BC 1.85.2).

## Actual Cardano scheme, not generic VRF

The exact-release production chain is `StandardCrypto` → `PraosVRF` → **crypto_vrf_ietfdraft03_verify**. Wrapper source and detailed trace are retained in vrf-wrapper-research.md and vrf-wrapper-sources/. The unversioned sodium `crypto_vrf_verify` instead delegates to draft13: it is the wrong oracle even though its ordinary proof is also 80 bytes. `PraosBatchCompatVRF` is separately bound to draft13's 128-byte batch-compatible format.

The source comments on CCL call its scheme draft06; the executable domains match the Cardano draft03 profile for these examples:

- Suite byte `0x04`, Edwards25519, SHA-512, old Elligator2 mapping, 32-byte compressed public key.
- Proof layout: compressed Gamma at bytes 0–31, little-endian 128-bit c at 32–47, little-endian 256-bit s at 48–79. Exact 80 bytes.
- Hash to curve: SHA512(`04 || 01 || encode(Y) || alpha`), take first 32 bytes and clear bit 255, then native `ge25519_from_uniform` Elligator2/cofactor map. Alpha is an arbitrary byte string at the primitive boundary; construction of Cardano protocol alpha is a distinct layer.
- Challenge: first 16 bytes of SHA512(`04 || 02 || H || Gamma || U || V`), using compressed 32-byte point encodings. No Y in this challenge transcript and no terminal zero byte.
- Native arithmetic uses `cn = -c mod L`, U = [cn]Y + [s]B, V = [cn]Gamma + [s]H. CCL instead computes point subtraction [s]B − [c]Y and [s]H − [c]Gamma. Equivalent for prime-order points, not automatically for mixed-order points.
- Output: 64-byte SHA512(`04 || 03 || encode([8]Gamma)`). No terminal zero, despite a misleading comment in native source.
- Draft13 differs in hash-to-curve (`ECVRF_edwards25519_XMD:SHA-512_ELL2_NU_` domain), adds Y and a trailing zero in the challenge, and appends zero in the output domain. All 2,021 fixed-envelope draft03 corpus inputs fail ordinary draft13 verification, including all three originals.

## Canonicality, subgroup and decoder rules

Native draft03 verification checks public key canonical y encoding, successful on-curve decode, and not small order. It does **not** demand prime-subgroup membership. Gamma must be canonical and decode on-curve, but has no explicit small-order or prime-subgroup rejection. The scalar guard (`s[31] & 240` followed by `sc25519_is_canonical`) enforces s < L: values with the high nibble zero are already below L. c is inherently bounded by its 16-byte representation. Both the verifier and native proof-to-hash enforce the scalar and Gamma checks.

CCL BcVrfVerifier checks canonical y through normalize/re-encode, rejects x=0 with sign bit 1, rejects small-order Y using [8]Y == identity, but never checks s < L. EcVrfVerifier relies on i2p GroupElement decoding/on-curve behavior and checks [8]Y == identity; it also omits s < L and lacks a separate explicit canonical-byte round trip. Its scalar multiplication behaves differently for high-bit scalars, as shown by the differing mismatch counts; its rejection of some s+kL values is not a canonical-s policy.

The pinned native decoder does not explicitly reject the x=0/sign=1 encoding in its point decode; the pk small-order guard rejects such pk examples. Gamma decoder behavior remains relevant. Do not silently impose “strict Ed25519” or prime-subgroup-only validation on this VRF profile. This finite corpus does not establish equivalence on valid mixed-order proofs, all noncanonical point representations, or complete group arithmetic.

## Public fixture corpus and results

Source fixture: [pinned sodium vrf_03.exp](https://github.com/IntersectMBO/libsodium/blob/dbb48cce5429cb6585c9034f002568964f1ce567/test/default/vrf_03.exp). Its paired vrf_03.c identifies the published draft03 Appendix A.4 examples. Only the **already published public keys, proofs and output hashes** from .exp were extracted. The upstream test executable was never run, since it generates/proves using seeds. Messages are empty, `72`, and `af82`.

2,048 rows:

- 3 published positive examples.
- 14 upstream-described negative mutations: Gamma/c/s bit changes, high-bit s changes, and truncated nonempty messages.
- 1,923 additional public mutations: all 640 proof-bit flips per example and three wrong-message cases. Some intentionally duplicate the upstream mutation bytes; counts are rows, not unique byte triples.
- 45 noncanonical scalar variants s+kL, k=1..15.
- 36 public-key/Gamma point boundaries: zero, identity, minus one, p, p+1, and x=0 sign-bit alternative.
- 27 malformed-envelope/null cases. These are tested on JVM only and **excluded before native invocation**, never padded/truncated for native calls.

Pinned draft03: 3 accepted, 2,018 rejected, 27 excluded. All three outputs match their upstream expected values and both JVM providers.

Exact-source EcVrfVerifier: 25 accepted, 1,996 rejected, 27 exceptions. **22 native mismatches**, all s+kL over-acceptance (k=1..8 on vector 0, k=1..7 on vectors 1 and 2).

Exact-source BcVrfVerifier: 48 accepted, 1,973 rejected, 27 exceptions. **45 native mismatches**, all tested s+kL variants accepted with the original output.

The downloaded **published Maven binary** produced byte-identical results to the exact-source build on all 2,048 rows. Class-load logging verifies both verifier classes and Ed25519Point/VrfResult were loaded from published.jar, not the locally compiled classes. This establishes the finding for that specific downloaded artifact/dependency profile as well as the pinned source subset. It does not prove reproducible compilation of the complete Maven artifact.

A concrete counterexample is `v0-s-plus-1L` in results.json: original empty-message proof's final 32 bytes are replaced with `41aa6b2c560b3038b5a133da52ea406b0f55edc256a787afe701677c0f602910`. Native rejects; both CCL verifiers accept. All untouched public inputs are retained in vectors.json and vectors.tsv.

These are compatibility findings, not evidence of an exploit against a deployed application and not a production security audit. The corpus has three authentic generic VRF positives, not mainnet-header golden vectors.

## Executed mixed-order equation probe

`MixedOrder.java` uses only the published Edwards basepoint B and the public order-2 point T=(0,−1), then constructs P=B+T. P encodes as `9599999999999999999999999999999999999999999999999999999999999999`; [8]P is nonidentity and [L]P=T, so it is mixed order, not a small-order point. No private scalar, key generation or proof creation is involved.

For public c values 0, 1, 2 and 17, the probe compares `[−c mod L]P` to `−[c]P`, with B controls. All four prime-order controls agree. For P, c=0 agrees and c=1,2,17 **differ**. For example at c=1 the native scalar-negation expression encodes `58666666666666666666666666666666666666666666666666666666666666e6`, while point negation encodes `9599999999999999999999999999999999999999999999999999999999999919`.

`check_mixed_order.py` independently reconstructs both expressions by public double-and-add using only the pinned native `crypto_core_ed25519_add` and `crypto_core_ed25519_sub` APIs. All eight cases agree byte-for-byte with CCL BC arithmetic; it also checks [8]P and [L]P. Thus this is a demonstrated equation discrepancy, not merely a source-reading concern. Results are in mixed-order.tsv and mixed-order-results.json.

This is **not a valid mixed-order VRF proof** and does not prove an accepted-proof counterexample for that second issue. Creating a specially constructed proof is outside this public verification-only task, and none was generated. Authentic published mixed-order proof fixtures or a separately authorized proof-construction study would be needed for that extension. A canonical-s guard alone is not approved as a complete fix. The bounded conclusion is an explicit compatibility blocker; no adapted verifier or mainrepo implementation was forced.

## API, errors and ownership contract

CCL `verify(byte[] publicKey, byte[] proof, byte[] alpha): VrfResult`:

- Null or non-32-byte key throws VrfException with `Invalid public key size. Expected 32 bytes, got <null or length>`.
- Null or non-80-byte proof throws VrfException analogously.
- Null alpha throws VrfException `Alpha must not be null`; empty alpha is valid.
- Decode/math failures inside verification are generally caught and collapsed to `invalid()`; VrfException is rethrown. Thus invalid input and some unexpected provider/math exceptions are not distinguishable from the result alone.
- Valid result contains the 64-byte output; invalid result has `valid=false, output=null`.
- `VrfResult.valid(output)` clones input once. Lombok-generated getter returns the underlying mutable array, and the public all-args constructor accepts an array without cloning. A Scala facade must own/copy buffers and never treat this class as an immutable consensus value.
- Inputs are not snapshotted at the entrypoint. A future facade needs immutable ownership or entry copies to avoid concurrent caller mutation.
- Native function returns 0 for valid and −1 for invalid, writes a 64-byte output only on success. Its ABI has no key/proof length parameters, so the caller must enforce 32/80 bytes before dispatch.
- Native proof_to_hash is **not proof verification**: it cannot establish a claim against a public key and message. Do not expose its output as verified without verify success.
- Haskell CertifiedVRF checks the recomputed output equals the separately claimed output; merely obtaining a boolean from a Java verifier omits this obligation.

A future safe facade should distinguish malformed envelope, cryptographic invalidity, and internal/provider failure; accept bytes of the already constructed protocol message; return an owned 64-byte value only after verification; and compare claimed output separately where the protocol carries it. Defining that interface does not fix the demonstrated algorithm mismatch.

## Reusable source and license obligations

For the BC public verifier path, the smallest inspected source set is VrfVerifier.java, VrfException.java, VrfResult.java, bc/BcVrfVerifier.java, bc/Ed25519Point.java, and bc/VrfUtil.java, plus BC's public X25519Field API and JDK SHA-512/BigInteger. Lombok is compile-time only for result getters/constructor. EcVrfVerifier.java is the alternative i2p path and requires the separate i2p artifact. The CardanoVrfInput/CardanoLeaderCheck helpers are a protocol layer and were not executed by this probe. In particular, the inspected CardanoVrfInput requires a 32-byte epoch nonce, whereas exact Haskell message construction also represents NeutralNonce with no nonce bytes; Java long also needs an explicit unsigned-slot encoding/domain policy. Do not infer complete protocol coverage from the helper name.

Retain the CCL MIT copyright/permission notice for copied source (saved CCL-LICENSE); preserve modification attribution and exact origin if adapted. Native source/fixtures retain their sodium ISC license (saved sodium-LICENSE) and cite original IETF fixture provenance. Dependency licenses/notices remain separately applicable; this packet is not a completed redistribution/legal review. The whole crypto-ext Maven artifact includes secret/prover APIs; using it as a dependency exposes broader surface even if never invoked. This research compiled only public verifier files and never called the prover path.

## Reproduction and evidence

Under vrf-slice-probe/: prepare_run.py builds the public corpus and calls only named draft03/draft13 native verify functions; run-jvm.sh compiles the seven unchanged public verifier source files plus a harness and runs both implementations; summarize.py records row-by-row and aggregate comparisons. Java compiler is invoked as `java com.sun.tools.javac.Main` because a javac launcher is absent. Full explicit classpath is used for compilation.

Retained: vectors.json/tsv, results.json, summary.json, jvm-results.tsv, published-jvm-results.tsv, published binary/POM/sources, Maven metadata, dependency jars, class-load evidence, source comparison, licenses, and SHA-256 manifest. The native artifact is hash-gated before loading. Verification tests use no sodium_init, randomness, secret fixtures, key derivation or proof creation. The separate mixed-order probe additionally calls only native public-point add/sub, with published public constants. Broad upstream test suites were not run. Build tooling was already available; no global install occurred.

## Remaining gates

Do not promote this to production or replace the existing runtime verifier based on these results. Needed before implementation acceptance: explicit canonical-s policy matching native; mixed-order scalar semantics; complete point canonical/decode behavior; additional authentic Cardano header/VRF public fixtures and alpha/claimed-output integration; adversarial and randomized public verification corpora; arithmetic/exception/ownership review; bounded resource behavior; dependency/license review; and clear separation between exact-source oracle evidence and official shipped binary provenance. The existing strict Ed25519 witness work stays separate.
