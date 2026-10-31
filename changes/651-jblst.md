### Changed (crypto, #651)

- BLS12-381 (the Leios BLS keys) now runs on `tech.pegasys:jblst` 0.3.18, the maintained Java binding of blst, at blst v0.3.17. It replaces `foundation.icon:blst-java` 0.3.2 and the portable libblst this repository built itself (the `blst-native` module is removed; ADR 0025 supersedes ADR 0012). The start-up check still refuses an unpinned library on a CPU without ADX/BMI2, and now pins jblst's library for each platform.
- Release candidates record a `native-identity` receipt, jblst's pinned libraries checked against the assembly, in place of the two-build native rebuild proof.
