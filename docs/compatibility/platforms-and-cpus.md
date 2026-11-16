<!-- SPDX-License-Identifier: Apache-2.0 -->
# Platforms and CPUs

Part of the [compatibility matrix](../compatibility.md); the register entry is `platforms-and-cpus` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, brought up to date on 2026-11-15 for the removal of the Scalus backend.

## Status as recorded

implemented: Linux x86-64 on any CPU (no ADX, BMI2 or AVX needed: the BLS12-381 library is a portable blst build, as the reference node's: blst v0.3.17 from jblst, for the Leios keys and the Plutus builtins, ADR 0025); the node checks the library (and `/proc/cpuinfo`) before a script can run and refuses with a typed `NativeLibraryError` event instead of a SIGILL. Until 2026-11-15 the Plutus builtins ran on `libscalus_crypto` (blst v0.3.15 inside, ADR 0020), which needed glibc 2.33 or newer and an executable `java.io.tmpdir`; it is no longer shipped

## Evidence

`BlstSuite`, the Plutus conformance BLS cases, `LeiosCertificateSuite`; `check_native_isa.py` over the Linux/amd64 libraries (jblst's: 885 ADX/BMI2 instructions; `libscalus_crypto`'s, until 2026-11-15: 907; all behind `__blst_platform_cap`; blst-java 0.3.2's library fails as the negative control, 655 ungated entries) and `check_native_identity.py` (jblst's libraries equal the hashes `Blst` pins); `BlstCheckMain` (three Plutus V3 scripts with the BLS12-381 builtins) on worker-a (older 2-core x86-64 CPU, no `adx`/`bmi2`) passed with the library of ADR 0012, which jblst replaced on 2026-10-30, and the library of blst-java was refused there

## Explicitly unchecked or unconfirmed

The worker-a run predates jblst; jblst's library has not been run on a CPU without ADX/BMI2. Linux aarch64, macOS and Windows use jblst's libraries (not run here); musl is unsupported; RocksDB was not run on a CPU without ADX/BMI2 beyond the preprod follower's earlier blocks; no Windows
