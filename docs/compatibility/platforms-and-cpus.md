<!-- SPDX-License-Identifier: Apache-2.0 -->
# Platforms and CPUs

Part of the [compatibility matrix](../compatibility.md); the register entry is `platforms-and-cpus` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: Linux x86-64 on any CPU (no ADX, BMI2 or AVX needed: the BLS12-381 libraries are portable blst builds, as the reference node's: blst v0.3.17 from jblst for Leios keys, ADR 0025, and blst v0.3.15 inside `libscalus_crypto` for Plutus, ADR 0020); glibc 2.33 or newer and an executable `java.io.tmpdir` for `libscalus_crypto`; the node checks both libraries (and `/proc/cpuinfo`) before a script can run and refuses with a typed `NativeLibraryError` event instead of a SIGILL or an unavailable builtin

## Evidence

`BlstSuite`, `NativeCryptoSuite`, `NativeCryptoAbsentSuite`, the Plutus conformance BLS cases, `LeiosCertificateSuite`; `check_native_isa.py` over both Linux/amd64 libraries (jblst's: 885 ADX/BMI2 instructions; `libscalus_crypto`'s: 907; all behind `__blst_platform_cap`; blst-java 0.3.2's library fails as the negative control, 655 ungated entries) and `check_native_identity.py` (jblst's libraries equal the hashes `Blst` pins); `BlstCheckMain` (three Plutus V3 scripts with the BLS12-381 builtins) on worker-a (older 2-core x86-64 CPU, no `adx`/`bmi2`) passed with the library of ADR 0012, which jblst replaced on 2026-10-30, and the library of blst-java was refused there

## Explicitly unchecked or unconfirmed

The worker-a run predates Scalus 1.4.0 and jblst; neither `libscalus_crypto` nor jblst's library has been run on a CPU without ADX/BMI2 (#407). Linux aarch64, macOS and Windows use jblst's libraries for Leios keys and their pinned `libscalus_crypto` (not run here); musl is unsupported; RocksDB was not run on a CPU without ADX/BMI2 beyond the preprod follower's earlier blocks; no Windows
