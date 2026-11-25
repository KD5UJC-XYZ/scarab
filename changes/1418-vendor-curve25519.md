### Changed (build, #1418)

- The Edwards-curve arithmetic behind Ed25519 verification (`StrictEd25519`, `ByronEd25519`) and VRF proving
  (`Draft03Prover`) is now built from source in the `crypto` module. The source of curve25519-elisabeth (MIT,
  weavechain/curve25519-elisabeth at revision `81bd79dbd0b323e5fc5729bca883d124b95b4e54`, the revision published as
  0.1.3; the repository has no tags) is vendored in `crypto/src/main/java/com/weavechain` with its licence notices,
  each file with an `Apache-2.0 AND MIT` SPDX line; the jar `com.weavechain:curve25519-elisabeth:0.1.3` is no
  longer a dependency. This is the first step of #1418, which hardens that code in later changes.
