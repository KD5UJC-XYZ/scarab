<!-- SPDX-License-Identifier: Apache-2.0 -->
# Platforms and CPUs

Part of the [compatibility matrix](../compatibility.md); the register entry is `platforms-and-cpus` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: Linux x86-64 on any CPU (no ADX, BMI2 or AVX needed: the BLS12-381 library is blst v0.3.15 built with `-D__BLST_PORTABLE__`, as the reference node's, `blst-native`, ADR 0012); the node checks the library and `/proc/cpuinfo` before a script can run and refuses with a typed `NativeLibraryError` event instead of a SIGILL

## Evidence

`BlstSuite`, the Plutus conformance BLS cases, `LeiosCertificateSuite`; `BlstCheckMain` (three Plutus V3 scripts with the BLS12-381 builtins) on worker-a (older 2-core x86-64 CPU, no `adx`/`bmi2`): passes, and the library of blst-java is refused there; speed on a CPU with ADX within 5 percent of blst-java's (dev-host, a recent desktop CPU)

## Explicitly unchecked or unconfirmed

Linux aarch64 and macOS use the libraries of blst-java (not run here); the other native libraries (RocksDB, secp256k1 JNI) were not run on a CPU without ADX/BMI2 beyond the preprod follower's earlier blocks; no Windows
