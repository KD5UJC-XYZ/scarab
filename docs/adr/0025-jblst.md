# 0025. BLS12-381 through jblst, the maintained Java binding of blst

- **Status**: Accepted
- **Date**: 2026-10-30
- **Issue**: #651
- **Supersedes**: the decision of [ADR 0012](0012-portable-blst.md) (its start-up check is kept)

## Context

[ADR 0012](0012-portable-blst.md) shipped a portable `libblst.so` that Scarab built itself
(blst v0.3.15, `-D__BLST_PORTABLE__`). It sat in a module placed before `foundation.icon:blst-java:0.3.2`
on the class path, because the library inside that jar runs ADX/BMI2 code with no CPU check. On
an x86-64 processor without those extensions it dies with SIGILL. Since
[ADR 0020](0020-plutus-native-crypto-through-scalus-crypto-jni.md), that library served only the
Leios BLS keys (`MinSig`).

- blst-java has not released since 0.3.2 (2023-09), which wraps blst's 0.3.10 release.
- Scarab carried the native build alone: the recipe, the reproducible rebuild (#229), a raw-DST
  adapter still exported by the committed binary (#408), and the class-path override.
- ADR 0012 named the way out: revisit "when Scalus or blst-java ship a portable build themselves
  (then drop the module and keep the check)".

`tech.pegasys:jblst` is Consensys's maintained Java binding of blst (Maven Central;
[Consensys-Incorporated/jblst](https://github.com/Consensys-Incorporated/jblst)). As read on 2026-10-30:

- **What it builds:** 0.3.18 (2026-07-30, tag `f69781237`) builds the blst submodule at `54e6e5567`,
  which is exactly blst **v0.3.17**. Upstream has no v0.3.18; the number is jblst's own.
- **What it ships:** Linux amd64 and aarch64, macOS x86_64 and aarch64, and Windows amd64, under the
  same `supranational.blst` SWIG package and the same resource scheme as blst-java.
- **Portability:** `scripts/check_native_isa.py` on its Linux/amd64 `libblst.so` finds every ADX/BMI2
  instruction behind blst's `__blst_platform_cap`: 0 ungated entries, 17 extension functions. The
  same check on blst-java 0.3.2 finds 655 ungated entries.

## Decision

**Use `tech.pegasys:jblst` 0.3.18 for BLS12-381, and remove blst-java and Scarab's own build of
libblst.**

- **Dependency:** `crypto` depends on jblst directly. Its POM's `runtime` dependency on Guava 18.0 is
  excluded: no jblst class refers to it (a scan of the jar's constant pools).
- **Removed:** the `blstNative` module and its directory, the Dockerfile that built the library, the
  raw-DST adapter, the assembly's `preferProject` rule for the library, and the native rebuild
  workflow with its two scripts (the rebuild-and-compare check and the rebuild trigger).
- **Start-up check kept:** the check of ADR 0012 moves to `crypto` (`xyz.kd5ujc.scarab.crypto.bls.Blst`).
  `Blst.pinned` holds the sha-256 of each platform library in the jar, and `LibraryBuild.Portable`
  now means "the pinned jblst library". The CPU admission rule and the known answers are unchanged.
- **CI:** `scripts/check_native_identity.py` checks the resolved jblst jar against `Blst.pinned` and
  runs the ADX/BMI2 check on its Linux/amd64 library.
- **Release candidates:** the same script checks the assembly. Its receipt is the candidate's
  `native-identity` evidence, in place of the two-build `native-reproducibility` proof, and the SBOM
  records the library as supplied by jblst and pinned at start-up.

**Deviation from the reference, approved by the owner (2026-10-30, #651).** The reference node links
libblst 0.3.15 (iohk-nix `overlays/crypto/libblst.nix`); Scarab now runs blst v0.3.17. Before the
switch, the 74 commits between the two tags were read (the record is on #651).

- **Unchanged:** no file of point validation and the subgroup checks (`e1.c`, `e2.c`), hash-to-curve
  (`map_to_g1.c`, `map_to_g2.c`, `hash_to_field.c`), aggregate verification and the pairing
  (`aggregate.c`, `pairing.c`) changed.
- **Changed:** the field arithmetic's implementation (constant-time inversion, the ADX carry chains),
  scalar multiplication's tolerance of low-order points, multi-scalar fixes, `keygen_v5` with an
  empty salt, and the binding headers.
- **Checked:** `MinSigVectorSuite` (cardano-base vectors), `LeiosCertificateSuite` and `BlstSuite`
  pass unchanged on jblst.

**The binding changed in one way that matters.** blst v0.3.17 reworked the SWIG `bytes_t`, and the
new JNI glue reads a `null` augmentation array instead of treating it as empty. The JVM died with
SIGSEGV in `jni_GetByteArrayElements` until `MinSig` called the overloads without the argument
(`hash_to(msg, dst)`, `core_verify(pk, true, msg, dst)`), which mean the same in blst. Callers pass
no `null` to jblst.

| What | Value |
|---|---|
| Artifact | `tech.pegasys:jblst:0.3.18`, jar sha-256 `6eea37289e9c484fb15d76454506de1c5ec0931054d19546bb200d38c8bef8cc` |
| blst | v0.3.17, commit `54e6e55674722fc2797ebb4bbb71b26d881eb4b8` |
| Linux/amd64 | `6a87d87cc982078dcf40f034f21959839d1fe20d45229d37de9c82c178f2a0ce` (portable, checked) |
| Linux/aarch64 | `b47e669bcd521a0cc3b5753beab0c9ddb5cc81988d5d555722de3149db75b52b` |
| macOS x86_64 | `17aa48bb47a6df714d68ebdfa622341866e3df5d5ba632c3abf526f92f7cbb98` |
| macOS aarch64 | `7fd78cc498f3f3998070f50d5645cff71b1af01be94699da393235a5cfebdbe4` |
| Windows amd64 | `3cbd35dbf7859829a38720444098b72cfe9d2d79c1f39c2e43fa26b94e1c99a5` |
| JNI exports | `crypto/src/test/resources/blst/jblst-exports.txt` |
| Licence | the jblst repository's `LICENSE` is MIT, while its POM says Apache-2.0; blst is Apache-2.0. Both texts ship (`licenses/jblst-0.3.18/LICENSE`, `crypto/licenses/LICENSE-blst`) and the component is recorded as `Apache-2.0 AND MIT` |

To update jblst:

1. Read blst's changes since the pinned commit for anything Cardano relies on.
2. Bump `V.jblst` and record the new jar and library hashes here and in `Blst.pinned`; `BlstSuite`
   and the start-up check fail until they match.
3. Regenerate the export list, and the licence entry if the texts changed.
4. Run `check` and `scripts/check_native_identity.py` on the new jar.

## Consequences

- One maintained artifact in place of a native build Scarab maintained alone: no recipe, no adapter,
  no rebuild gate, no class-path ordering. #408 is moot, and the bundle layout loses
  `classes/blst-native`.
- **Supply chain:** the binaries are built by Consensys CI, not reproduced here. The trust rests on
  the pinned hashes checked at start-up and in CI, and on the ADX/BMI2 check. That is what
  [ADR 0020](0020-plutus-native-crypto-through-scalus-crypto-jni.md) accepts for `scalus-crypto-jni`.
  A one-time rebuild of jblst from its tag, to compare bytes, was not done.
- **Platforms:** Linux/aarch64, macOS and Windows now run blst v0.3.17 from jblst instead of the
  0.3.10 release inside blst-java. Only Linux/amd64 is deployed and verified.
- **Not re-run:** the no-ADX/BMI2 smoke of ADR 0012 (`BlstCheckMain` on a CPU without the extensions)
  was not re-run for jblst; it is tracked with #407's run of the same kind. Speed was not re-measured;
  `BlstCheckMain bench` gives the rates.
- **Revisit when:** jblst stops releasing or ships a library that fails the ADX/BMI2 check, a blst
  release changes validation, hash-to-curve or aggregation, or the reference node moves to a blst
  version that jblst does not build.
