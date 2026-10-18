# 0012. One portable libblst for every x86-64 CPU, shipped where blst-java loads it

- **Status**: Accepted
- **Date**: 2026-10-16
- **Issue**: #93 (roadmap B25)

## Context

A preprod follower on an older 2-core x86-64 CPU (no `adx`, no `bmi2` in `/proc/cpuinfo`) died with
SIGILL, exit 134, in `libblst.so` `mulx_mont_384` when a Plutus script called a BLS12-381 builtin
(`JVMPlatformSpecific.bls12_381_G1_uncompress`, `supranational.blst.P1.<init>`). The JVM exits on
the signal without an error event, so the node crash-looped. The native library is the one inside
`foundation.icon:blst-java:0.3.2`, a dependency of Scalus that Scarab's `crypto` module (Leios
certificates, `MinSig`) uses too.

How blst-java loads its library (`blstJNI.<clinit>`, the SWIG output of blst `bindings/java`,
read from the class file):

1. `resName = <os.name up to the first blank>/<os.arch>/<System.mapLibraryName("blst")>`, here
   `Linux/amd64/libblst.so`.
2. `blstJNI.class.getResourceAsStream(System.getProperty("supranational.blst.jniResource", resName))`:
   a name without a leading slash is relative to the package, so the resource is
   `supranational/blst/Linux/amd64/libblst.so` of the first class path entry that has it.
3. Found: the bytes are copied to a temporary directory and `System.load`ed. Not found:
   `System.loadLibrary("blst")` (`java.library.path`).

The library in the jar was built on a CI runner by `bindings/java/run.me` of blst v0.3.10
(`icon-project/blst-java` workflow `build.yml`). blst's `build.sh` adds `-D__ADX__` when the build
machine's `/proc/cpuinfo` has `adx`, and then compiles `mulx`/`adcx` code (the `mulx_mont_384`
family, 868 such instructions in the jar's `libblst.so`) with no run-time check. Any x86-64 CPU
before Intel Broadwell or AMD Zen is affected. blst offers `-D__BLST_PORTABLE__` for this; from
v0.3.11 it keeps both code paths and chooses at run time (`__blst_platform_cap`, set from CPUID),
while in v0.3.10 the flag only selects the `mulq` code and no ADX code is built at all.

The reference node has the same exposure and the same answer: `cardano-crypto-class` links
`libblst >= 0.3.14`, and iohk-nix (`overlays/crypto/libblst.nix`, v0.3.15) builds it with
`./build.sh -D__BLST_PORTABLE__`, with the comment that this lets "MULX, and similar stuff [be]
run-time detected, and as such blst built on newer hardware should still work on older".

## Decision

**Ship our own build of blst, portable, as the resource blst-java loads, from a module that comes
before blst-java on the class path.**

- `docker/blst.Dockerfile` builds `libblst.so` for Linux/amd64 from supranational/blst **v0.3.15**
  (commit `6d960cd05d6fe2b5bc9ba161edf0c1a131b87c4c`, the version iohk-nix builds for the reference
  node) with `bindings/java/build.sh -D__BLST_PORTABLE__`, the recipe of blst-java's workflow: on
  `ubuntu:22.04` (digest pinned; gcc 11.4.0, SWIG 4.0.2, binutils 2.38, JDK headers of
  openjdk-17 17.0.20.1; the same toolchain as the upstream workflow, and no glibc symbol above
  2.14 is needed, as for the upstream library). The source is fetched by commit and the build stops if `git rev-parse HEAD` differs.
  Output sha-256 of `libblst.so`:
  `8d158308ec8179a2e33eb1b26c3e09621307031715877867b46af11ebf884235` (a rebuild with `--no-cache`
  gives the same bytes).
- The sbt project `blstNative` (`blst-native/`) holds it at
  `supranational/blst/Linux/amd64/libblst.so`, the licence text of blst (`LICENSE-blst`, Apache-2.0)
  and `Blst`, the start-up check below. `crypto` and `plutus` depend on it, so it precedes
  `blst-java` on every class path that can reach blst (sbt puts the classes of the projects before
  the library jars; so does the bundle layout `classes/*` before `lib/*`). The JNI glue is the same
  SWIG output as blst-java's, so the unchanged `blstJNI.class` of blst-java runs against it
  (`BlstSuite` checks that every native method of `blstJNI` is exported by the library).
- No code is needed in the callers and no system property: Scalus and `MinSig` find the right
  library by themselves, and the same library is used for every CPU. Linux/aarch64 and macOS keep
  the libraries of blst-java: aarch64 has no ADX, and a macOS build does not run in this project's
  containers; this record is revisited if a platform other than Linux/amd64 is run seriously.

Rejected: (b) a system property or a `System.load` before blst-java's (it must be set before
`blstJNI` initialises, so every entry point, the tools and every test JVM would have to remember
it, and a forgotten one crashes instead of failing); (c) a fork or a patched blst-java on
Maven (a published artefact to maintain, for a library that ships two resource paths).
Also rejected: building v0.3.10 (the version blst-java wraps) with `__BLST_PORTABLE__`: it is the
`mulq`-only code on every CPU, with no dispatch; it measures the same here (below) but it is not
what the reference node runs.

### Start-up check

`follow` runs `Blst.verify` before anything evaluates a script (belt and braces; a SIGILL cannot be
caught):

1. `Blst.build` resolves the resource exactly as `blstJNI` will (the property included), without
   initialising `blstJNI`, and compares the sha-256 of its bytes with `Blst.portableSha256`:
   `Portable`, `Other(sha)`, or `OnLibraryPath` (no resource).
2. `Blst.cpu` reads `/proc/cpuinfo` (the features every processor in the file has: `adx`, `bmi2`).
3. `Blst.admissible`: on Linux/amd64 a library that is not the portable one is refused unless the
   processor is shown to have both features (an unreadable `/proc/cpuinfo` is not a proof). The
   refusal is the typed `BlstProblem.LibraryNeedsMissingCpuFeatures`, carried in the
   `NodeError` `NativeLibraryError`, and logged as an `error` event; exit code 1.
4. Known answers (`G1Uncompress`, `G2Uncompress`, `Pairing`) are computed; a wrong answer or a
   `LinkageError` (a native method the library lacks) is `BlstProblem.Unusable`.
5. The `blst` event reports platform, CPU features and library build.

`BlstCheckMain` (`node/tools`) runs the check, three Plutus V3 scripts with the BLS12-381 builtins
through the node's evaluator and, with `bench`, rates. It is how a bundle is verified on a CPU
without ADX.

### Provenance and how to update

| What | Value |
|---|---|
| blst source | supranational/blst v0.3.15, commit `6d960cd05d6fe2b5bc9ba161edf0c1a131b87c4c` (2025-06-06), Apache-2.0 |
| Recipe | `bindings/java/build.sh -D__BLST_PORTABLE__` (compiles `cc -O2 -fno-builtin -fPIC -D__ADX__ -fvisibility=hidden -D__BLST_PORTABLE__`, links the SWIG glue and the pinned raw-byte DST adapter with `g++ -shared ... -Wl,-Bsymbolic`) |
| Toolchain | `ubuntu@sha256:5ec03bb3441e8b0bf3b4f9cd4629a1ae763010dc3035bb8da3ae6cf026486401` (22.04), g++ 11.4.0-1ubuntu1~22.04.3, SWIG 4.0.2, openjdk-17-jdk-headless 17.0.20.1+1-1~22.04, binutils 2.38-4ubuntu2.12 |
| Result | `blst-native/src/main/resources/supranational/blst/Linux/amd64/libblst.so`, 310,400 bytes, sha-256 `5e5176a1056c0fcf5b253315fefb997466187f79977a0f65e1ee78e74c569e21`; `__blst_platform_cap` present; 754 `mulx`/`adcx` instructions behind the run-time switch |
| The library it replaces | `foundation.icon:blst-java:0.3.2` (jar sha-256 `f668af639696199e8b9f47e17a9ede4426c888fbc300aec84689f56f39ed6238`), `Linux/amd64/libblst.so` sha-256 `bd250731c0805cab213c1db860dbcd3bb8848955aa1da7a6fc81a11235b92817`, blst v0.3.10 from the workflow, gcc 11.4.0, built with `-D__ADX__` and no portable switch |
| JNI exports | every `Java_supranational_blst_blstJNI_*` symbol of the jar's library is in ours (`blst-native/src/test/resources/blst-exports.txt` is the export list of the shipped library) |

To update blst: change `BLST_COMMIT` in `docker/blst.Dockerfile`, run
`docker build -f docker/blst.Dockerfile --target artifact -o blst-native/out .`, copy
`blst-native/out/libblst.so` over the resource, `blst-native/out/exports.txt` over
`blst-native/src/test/resources/blst-exports.txt`, put the new sha-256 into `Blst.portableSha256`
(`BlstSuite` and the start-up check fail until it matches), refresh the table above and the
licence, and run `check`, the Plutus conformance suite (it contains the BLS12-381 cases) and
`BlstCheckMain` on a CPU without ADX. When blst-java changes its `blstJNI` (a new release of
Scalus), the export test says whether the library still has every native method it calls.

The 2026-10-18 raw-DST adapter update (#175) preserves the blst source, portable flags, existing JNI
exports and curve arithmetic. It adds two byte-array hash/compress entry points, pinned to the
upstream Scalus fix; [adapter provenance](../../blst-native/native/README.md) and
[executed Haskell vectors](../../fixtures/reference/plutus-boundaries/README.md) record the ABI and
new known answers. The earlier three-answer no-ADX performance measurements below concern the prior
artifact. The new artifact passed a separate offline no-ADX/BMI2 smoke on2026-10-18: CPU feature
set empty, portable library selected, all five startup known answers and all three public builtin
scripts passed, exit0. An isolated network-disabled container loaded only the public bundle and
compiled native overlay read-only; no operational node or configuration changed.

## Consequences

- The node starts and evaluates BLS12-381 builtins on every x86-64 CPU. Verified on worker-a (older
  x86-64 CPU, 2 cores, no `adx`/`bmi2`): `BlstCheckMain` with the three builtin scripts
  succeeds and reports 26,829 G1 and 13,412 G2 uncompressions, 442 pairings and 356 Leios
  certificates (24 seats) per second; with the library of blst-java on the same class path the
  start-up check refuses to start with the typed error instead of a SIGILL.
- Speed on a CPU that has ADX does not suffer. Measured on dev-host (a recent desktop CPU, in the
  `scarab-jdk` container, operations per second, two runs each of the portable library and of
  blst-java's): G1 uncompress 68,451 and 68,793 against 65,446 and 65,942; G2 35,274 and 33,192
  against 34,737 and 35,685; pairing (Miller loop and final verification) 1,274 and 1,295 against
  1,361 and 1,334; Leios certificate of 24 seats 994 and 933 against 1,016 and 955. The two are
  within the run-to-run spread (pairing and certificate about 3 to 5 percent lower). A v0.3.10
  `mulq`-only build measured 76,315, 38,200, 1,375 and 1,030 on the same machine, so `mulx` does not
  pay on this CPU either. BLS12-381 is a handful of operations per Plutus script and a handful of
  signatures per Leios certificate, far below the cost of block validation.
- The repository carries a 310 kB binary, with its source, build recipe and checksum recorded here.
  The previous libraries stay in the jar for the other platforms and are not removed from the class
  path.
- The bundle layout gains a module directory: `classes/blst-native` (the projects are
  `classes/<directory>`, and it must precede the jars in `classpath.txt`, as `runtime-classpath.txt`
  has it).
- Revisit when: Scalus or blst-java ship a portable build themselves (then drop the module and keep
  the check), when the Dijkstra/Leios work needs a blst feature newer than v0.3.15, or when a
  platform other than Linux/amd64 is deployed.
