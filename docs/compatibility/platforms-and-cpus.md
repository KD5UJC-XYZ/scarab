<!-- SPDX-License-Identifier: Apache-2.0 -->
# Platforms and CPUs

Part of the [compatibility matrix](../compatibility.md); the register entry is `platforms-and-cpus` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: Linux x86-64 on any CPU (no ADX, BMI2 or AVX needed: the BLS12-381 libraries are blst v0.3.15 built with `-D__BLST_PORTABLE__`, as the reference node's: `blst-native` for Leios keys, ADR 0012, and inside `libscalus_crypto` for Plutus, ADR 0020); glibc 2.33 or newer and an executable `java.io.tmpdir` for `libscalus_crypto`; the node checks both libraries (and `/proc/cpuinfo`) before a script can run and refuses with a typed `NativeLibraryError` event instead of a SIGILL or an unavailable builtin

## Evidence

`BlstSuite`, `NativeCryptoSuite`, `NativeCryptoAbsentSuite`, the Plutus conformance BLS cases, `LeiosCertificateSuite`; `check_native_isa.py` over both Linux/amd64 libraries (907 ADX/BMI2 instructions each, all behind `__blst_platform_cap`; blst-java's library fails as the negative control); `BlstCheckMain` (three Plutus V3 scripts with the BLS12-381 builtins) on worker-a (older 2-core x86-64 CPU, no `adx`/`bmi2`): passes, and the library of blst-java is refused there; speed on a CPU with ADX within 5 percent of blst-java's (dev-host, a recent desktop CPU)

## Explicitly unchecked or unconfirmed

The worker-a run predates Scalus 1.4.0 and used blst-java's Plutus path; `libscalus_crypto` has not been run on a CPU without ADX/BMI2. Linux aarch64 and macOS use blst-java's libraries for Leios keys and their pinned `libscalus_crypto` (not run here); musl is unsupported; RocksDB was not run on a CPU without ADX/BMI2 beyond the preprod follower's earlier blocks; no Windows
