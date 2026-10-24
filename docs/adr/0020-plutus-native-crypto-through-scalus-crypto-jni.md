# 0020. Plutus native crypto through scalus-crypto-jni

- **Status**: Accepted; partially supersedes [0012](0012-portable-blst.md) (its Plutus part)
- **Date**: 2026-10-23
- **Issue**: roadmap B11, I1; follow-ups [#406](https://github.com/KD5UJC-XYZ/scarab/issues/406) to [#409](https://github.com/KD5UJC-XYZ/scarab/issues/409)

## Context

Scalus 1.4.0 fixes the four Scalus deviations Scarab corrected in its wrapper: the variant-A
`verifyEd25519Signature` costing (IN-72), the V3 `NewConstitution` context (IN-73), the binary
domain separation tag of `bls12_381_G1/G2_hashToGroup` (IN-74 item 3) and
`verifyEcdsaSecp256k1Signature` on `r = 0` or `s = 0` (IN-75). Without those corrections the
conformance suite stays at 1,009 of 1,009 and `check` passes.

1.4.0 also changes how the cryptographic builtins reach native code. It drops blst-java and
scalus-secp256k1-jni and depends on `org.scalus:scalus-crypto-jni:0.1.0`, whose
`scalus.crypto.jni.CryptoJni` loads one library, `libscalus_crypto`, through
`org.scijava:native-lib-loader:2.5.0`. Ed25519 (`Sodium`), ECDSA and Schnorr over secp256k1
(`Secp256k1`) and every BLS12-381 operation (`Blst`) run there. Read from the Scalus 1.4.0 class
files, 22 builtins reach it. Eighteen do so through `JVMPlatformSpecific`. The compress and equal
builtins of G1 and G2 do so through the methods of `G1Element` and `G2Element`. That is the set
`ScalusEvaluator.nativeCrypto` guards.

How the library loads, read from the class files:

1. `CryptoJni.<clinit>` calls `NativeLoader.loadLibrary("scalus_crypto")` and catches only
   `IOException` and `UnsatisfiedLinkError`. Either one is printed to stderr and leaves
   `isEnabled` false. Every entry point then throws `IllegalStateException` from `requireEnabled`.
2. `NativeLoader.loadLibrary` first tries `System.loadLibrary` (`java.library.path`). Only after
   that fails does it extract `natives/<platform>/libscalus_crypto.so`, then `<platform>/…`, then
   `META-INF/lib/<platform>/…`, to a new directory under `java.io.tmpdir`, and `System.load` it from
   there.
3. If `java.io.tmpdir` is missing or unwritable, native-lib-loader's own static initialisation
   fails. `CryptoJni` then throws `ExceptionInInitializerError`, and on every later use
   `NoClassDefFoundError`. Both are `LinkageError`s, which `NonFatal` does not catch. This was
   observed in the JDK image. If `java.io.tmpdir` is mounted `noexec`, the `dlopen` fails with an
   `UnsatisfiedLinkError` that `CryptoJni` catches: it prints `Failed to load scalus_crypto native
   library` to stderr and `isEnabled` is false. This was observed with the assembled JAR in the
   pinned release runtime base (`eclipse-temurin`, Ubuntu 22.04, glibc 2.35) under
   `--tmpfs /tmp:rw,noexec`; there `BlstCheckMain` already refuses at blst-java's extraction to the
   same directory, and with `-Djava.io.tmpdir` on an executable mount both checks pass.

Without the library, every Plutus V1/V2 script that verifies an Ed25519 signature is
unavailable, not just the newer BLS12-381 and secp256k1 builtins. Before 1.4.0, Ed25519 ran on the
JVM.

### Provenance

| What | Value |
|---|---|
| Jar | `org.scalus:scalus-crypto-jni:0.1.0`, sha-256 `042b621f0e292713ef20f816cdd11c5037e80649c3e5c42a7308b071c7c1ceac` (Maven Central) |
| `natives/linux_64/libscalus_crypto.so` | 1,689,968 bytes, sha-256 `ca17484cefcd8e6ecf5c706d1fa1144a874283516eb78e52a535daa02894934a` |
| `natives/linux_arm64/libscalus_crypto.so` | 1,626,208 bytes, sha-256 `8473caf6b844af4a40e502ffb8d6e210c3ff43867558ef3aeff17359198f293d` |
| `natives/osx_64/libscalus_crypto.dylib` | sha-256 `d6ed19b01a08a369ed28f46edb4b5ffa7c2c59e1f7c9f5b3539bd4650b8e8acf` |
| `natives/osx_arm64/libscalus_crypto.dylib` | sha-256 `6897d67fbd5fc8df05093fbe80c244401693afd3baaedc2e9c7977fed31b7ff1` |
| `natives/windows_64/scalus_crypto.dll` | sha-256 `42325071a91a1bb8072d161832f879338c47ec243b095c4164ec51173eb6bda7` |
| Source | scalus3/scalus tag `crypto-jni-v0.1.0` (`1d04d067`). Statically linked: libsodium (input-output-hk fork, `dbb48cce`) and libsecp256k1 v0.3.2, the revisions cardano-node 11.1.3's `flake.lock` pins through iohk-nix; blst v0.3.15 (`6d960cd0`) built with `-D__BLST_PORTABLE__` |
| Toolchain | nix, on the upstream CI runner. The `.comment` section names clang 21.1.2 and GCC 14.3.0 (runtime libraries) |
| Loader | `org.scijava:native-lib-loader:2.5.0` (BSD-2-Clause) |

The binaries are **not byte-verified**. Scarab has not rebuilt them, so the hashes pin the bytes
Maven Central serves, not a reproduction from source. The `RUNPATH` of both Linux libraries names
the upstream CI runner's work directory and `/nix/store/…` directories. Their only `NEEDED` libraries
are `libc.so.6` (and `ld-linux-aarch64.so.1`), which the JVM has already loaded, so these paths are
not searched in practice. Still, they leak build paths and are a reason to rebuild the library
ourselves later ([#406](https://github.com/KD5UJC-XYZ/scarab/issues/406)).

Platform limits, read with `objdump -T`:
- Both Linux libraries need glibc 2.33 or newer (`fstat@GLIBC_2.33`). Upstream's README says
  2.34.
- musl is not supported.
- Only Linux/amd64 runs in this project's CI and containers. The other platforms are pinned but
  not exercised.

## Decision

**Adopt Scalus 1.4.0 with scalus-crypto-jni, and make the library's presence a start-up
condition, as ADR 0012 did for libblst.**

- The wrapper corrections that 1.4.0 makes unnecessary are removed:
  - the variant-A Ed25519 costing;
  - the ECDSA builtin replacement;
  - the raw-DST `hashToGroup` adapter and its ASCII-only refusal;
  - `BackendFailure.BinaryDstUnavailable` and `NativeHashToGroupUnavailable`;
  - the `ScalusDeviations` context patch.
- Every builtin the library backs is wrapped by `CorrectedVm.nativeGuarded`. It checks
  `NativeCrypto.available`, which never throws: an initialisation `LinkageError` counts as not
  loaded. When the library is missing, the builtin returns `EvalFailure.Unsupported`
  (`BackendFailure.BuiltinUnavailable`). That is never a script verdict or a consensus predicate
  failure.
- **Start-up check (`NativeCrypto.verify`)**. `follow` runs it after `Blst.verify`, before
  anything evaluates a script, and `BlstCheckMain` runs it too:
  1. `NativeLibraryUtil.getArchitecture` names the platform. A platform without a pinned library is
     `UnsupportedPlatform`.
  2. `locate` resolves the library in native-lib-loader's order without loading it: a file on
     `java.library.path` first, then the three resource locations. `admissible` accepts it only if
     its sha-256 equals the platform's pin in `CryptoPlatform`. A library that would shadow the pin
     from `java.library.path` is refused (`Unpinned`), as is a missing one (`Missing`).
  3. `CryptoJni` is initialised. Failure is `NotLoaded`, carrying the `LinkageError` when there is
     one.
  4. Known answers, each with a valid and a forged case, so that a library answering `true` to
     everything fails:
     - Ed25519 (RFC 8032 test 1);
     - ECDSA (the low-s RFC 6979 vector of `Secp256k1Suite`);
     - Schnorr (BIP-340 vector 0);
     - G1/G2 compression;
     - pairing bilinearity and a mismatch;
     - `hashToGroup` with message `3f` and tag `90`, against the pinned Haskell answers of
       `fixtures/reference/plutus-boundaries/hash-vectors.jsonl`.

  A refusal is `NativeLibraryError(NativeLibraryProblem.PlutusCrypto(…))`, logged as an `error`
  event, with exit code 1. Success logs a `plutusCrypto` event.
- **No-ADX/BMI2 evidence.** `scripts/check_native_isa.py` reads the disassembly of a library. It
  requires every function holding an ADX or BMI2 instruction to be reachable only through
  references guarded by `__blst_platform_cap`, and none of those functions to be exported.
  - `libscalus_crypto` linux_64 has 907 such instructions (299 `adcx`, 287 `adox`, 321 `mulx`) in
    18 functions, all of them gated. These count mnemonics; ADR 0012's 754 for the portable
    library counts every disassembly line that mentions `mulx` or `adcx`, call targets included
    (620 by mnemonic). No other part of the library (libsodium, libsecp256k1) uses
    the extensions.
  - Scarab's portable `libblst.so` gives the same result.
  - blst-java 0.3.2's library fails with 655 ungated entries. That is the negative control.
  - CI runs the check on both shipped Linux/amd64 libraries.
  - This reads code; it does not run it on a CPU without ADX. An offline smoke on such a CPU
    (`BlstCheckMain`, as in ADR 0012) remains the stronger evidence and is still to be recorded for
    this library ([#407](https://github.com/KD5UJC-XYZ/scarab/issues/407)).
- **Tests.** `NativeCryptoSuite` checks:
  - the five pins against the jar;
  - the loader's resource name;
  - the check passing in the test JVM;
  - the admission rule;
  - that the known answers derive independently (BouncyCastle, the Haskell vectors).

  `NativeCryptoAbsentSuite` runs in a forked JVM with a missing `java.io.tmpdir`. There, the check
  refuses with `NotLoaded`. The Ed25519 (V2), secp256k1 and BLS12-381 builtins are
  `BuiltinUnavailable`, and a script without them still succeeds.
- **The raw-DST adapter.** Plutus was its only user, so the `RawDst` JNI class and its two start-up
  known answers are removed. Its entry points stay in the committed, byte-verified `libblst.so`
  until the next rebuild of that library: removing `raw-dst.c` changes the reproducibility inputs
  and the binary. They are unreachable from the JVM, since no class declares those native methods
  ([#408](https://github.com/KD5UJC-XYZ/scarab/issues/408)).
- **Bouncy Castle** is pinned at 1.86, the version Scalus 1.4.0 depends on, so the declared and
  resolved versions agree.

What [0012](0012-portable-blst.md) still governs: the portable `libblst.so` of `blst-native`, its
reproducible rebuild and its start-up check. blst-java now serves only the Leios BLS keys
(`MinSig`), because Scalus's `Blst` has no key generation.

Rejected:
- (a) Keeping blst-java and scalus-secp256k1-jni beside Scalus 1.4.0. Scalus no longer calls
  them, so they would be dead native code.
- (b) Rebuilding `libscalus_crypto` in this repository now. It is the eventual answer to "not
  byte-verified" and to the `RUNPATH` leak, but it means a second native build pipeline. It is
  tracked as [#406](https://github.com/KD5UJC-XYZ/scarab/issues/406).
- (c) Starting without the library and failing per script. Every V1/V2 Ed25519 script would be
  unavailable, and a follower would stall on the first such block instead of refusing at start.

## Consequences

- A node needs, on Linux, glibc 2.33 or newer and a `java.io.tmpdir` that exists, is writable and
  allows execution. Otherwise it refuses to start with `NativeLibraryError`. The release images
  already mount `/tmp` with `exec`.
- The trusted computing base grows to the upstream nix build of `libscalus_crypto`, pinned by hash
  but not reproduced. The SBOM marks the Linux/amd64 library as runtime-verified (start-up pin and
  known answers), not source-rebuilt.
- The Plutus builtins and Scarab's MinSig run different blst builds: the same v0.3.15 source,
  compiled by two toolchains.
- Revisit when:
  - scalus-crypto-jni changes: update the five pins, the jar hash, the licence texts in
    `plutus/licenses/` and this record, and rerun the ISA check;
  - Scarab rebuilds the library from source;
  - a platform other than Linux/amd64 is deployed;
  - Scalus gains BLS key generation (then [0012](0012-portable-blst.md)'s module could go).
