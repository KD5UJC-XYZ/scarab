<!-- SPDX-License-Identifier: Apache-2.0 -->
# jblst JNI export contract

**Status: Current fixture contract.**

`jblst-exports.txt` lists the JNI functions (`Java_supranational_blst_blstJNI_*`) that the Linux/amd64
`libblst.so` of `tech.pegasys:jblst` 0.3.18 exports. That jar has sha-256
`6eea37289e9c484fb15d76454506de1c5ec0931054d19546bb200d38c8bef8cc`; its library has sha-256
`6a87d87cc982078dcf40f034f21959839d1fe20d45229d37de9c82c178f2a0ce` and is blst v0.3.17
(`54e6e5567`). It is an ABI regression fixture, not a cryptographic correctness oracle.

## Provenance and identity

Generated with `nm -D --defined-only libblst.so | awk '{print $3}' | grep '^Java_' | sort` from that
library inside the jar. Repository-wide fixture names and SHA-256 identities are pinned by
`scripts/check_test_coverage.py`; those identities detect changes and do not certify the expected outcomes.

## Assertions and limits

`BlstSuite` checks that every native method of jblst's `blstJNI` is exported by the library it ships
(the SWIG glue and the class match). Update the list only together with the jblst version
(`project/Dependencies.scala`) and the pinned hashes in `Blst`. Point and signature correctness and the
start-up check are separate tests.

## Changes and reproduction

Retain original payloads and expected results. Explain every intentional change, with its independent
evidence, under the [project evidence process](../../../../../docs/process.md).
